// TODO
// Add velocity (ramp up, ramp down, set value, randomize) output with note value in OSC event
// Add randomize and lock controls for most parameters
// Add notes from chords controls
// Add control to rotate note assignment to points
// Implement presets
// Debounce other controls (as they show late message when dragged) - also try to reduce debounce time to 10ms
// Write docs
HarmonySequencer {
    const c_maxTriggerCount = 16;
    const c_maxPointCount = 64;
    const c_uiUpdateOscPath = "/pointPosUpdate";
    const c_updatePointsTriggeredStateOscPath = "/triggerCrossing";
    const c_debounceTime = 0.1;
    const c_arrowUpKeycode = 38;
    const c_arrowDownKeycode = 40;
    const c_shiftModifier = 131072;
    const c_leftMouseButton = 0;

    classvar cv_quantizedValues;
    classvar cv_quantizedLabels;
    classvar cv_scales;
    classvar cv_scaleLabels;
    classvar cv_rootLabels;
    classvar cv_presets;
    classvar cv_parameterNames;

    var i_debugMode;
    var i_window;
    var i_userView;
    var i_triggerCheckboxContainer = nil;
    var i_server;
    var i_pointsPos;
    var i_pointsTriggerState;
    var i_oscFuncs;
    var i_pointsSynth;
    var i_pointsBus;
    var i_notesBus;
    var i_synthClearRoutine;
    var i_currentTriggerSynths = #[];
    var i_synthsToClear = #[];
    var i_nextDebounceAction = nil;

    var i_parameters = nil;
    var i_parameterUiControls = nil;

    *new { |server, debug=false|
        // Initialize class vars
        cv_quantizedValues = [0, 1/16, 1/8, 1/4, 1/2, 1.5/16, 1.5/8, 1.5/4, 1.5/2];
        cv_quantizedLabels = ["None", "16th", "8th", "Quarter", "Half", "Dotted 16th", "Dotted 8th", "Dotted quarter", "Dotted half"];
        cv_scales = [Scale.chromatic, Scale.majorPentatonic, Scale.minorPentatonic, Scale.ionian, Scale.dorian, Scale.phrygian, Scale.lydian, Scale.mixolydian, Scale.aeolian, Scale.locrian];
        cv_scaleLabels = ["chromatic", "minorPentatonic", "majorPentatonic", "ionian", "dorian", "phrygian", "lydian", "mixolydian", "aeolian", "locrian"];
        cv_rootLabels = ["C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"];
        cv_presets = Dictionary.newFrom([
            default: [8, [true, false, false, false, false, false, false, false], ]
        ]);
        cv_parameterNames = (
            triggerCount: \triggerCount,
            triggers: \triggers,
            bpm: \bpm,
            loopLength: \loopLength,
            scaleIndex: \scaleIndex,
            rootIndex: \rootIndex,
            octaveOffset: \octaveOffset,
            globalOffset: \globalOffset,
            quantizedOffsetIndex: \quantizedOffsetIndex,
            fineOffset: \fineOffset,
            fineOffsetJitterAmount: \fineOffsetJitterAmount,
            fineOffsetJitterFrequency: \fineOffsetJitterFrequency,
            speedOffset: \speedOffset,
            speedOffsetJitterAmount: \speedOffsetJitterAmount,
            speedOffsetJitterFrequency: \speedOffsetJitterFrequency,
            activePointCount: \activePointCount,
            probability: \probability,
            lowNote: \lowNote,
            highNote: \highNote,
            paused: \paused,
        );

        ^super.new.init(server, debug);
    }

    init {
        |server, debug|

        i_debugMode = debug;
        i_server = server;
        i_oscFuncs = Array.new();
        i_pointsPos = Array.fill(c_maxPointCount, 0);
        i_pointsTriggerState = Array.fill(c_maxPointCount, 0);

        i_synthClearRoutine = Routine({
            while ({i_synthsToClear.size > 0}) {
                var removed = Array.new();
                i_server.bind {
                    i_synthsToClear.do { |synth, i| if (synth.isRunning) {
                        synth.free;
                        removed = removed.add(i);
                    } };
                    removed.reverseDo { |index| i_synthsToClear.removeAt(index) };
                };
                this.prDebugPrint("Cleared"+removed.size+"synth,"+i_synthsToClear.size+"left");
                i_server.latency.yield;
            };
            this.prDebugPrint("Done clearing");
        });

        i_notesBus = Bus.control(i_server, c_maxPointCount);
        this.prInitializePointsSynth();
        this.registerPointTriggerAction({ |pointIdx, note|
            i_pointsTriggerState[pointIdx] = 1.0;
        });
        this.prInitializeParameters();
        this.setParameterValues();

        this.prDebugPrint("Done initializing");
    }

    /** Print text if debug mode is on
    * @param text: text to print
    */
    prDebugPrint { |text|
        if (i_debugMode) { text.postln };
    }

    /** Initialize parameters state */
    prInitializeParameters {
        var setIntWithSpec = { |self, value| self.prValue = self.spec.constrain(value).asInteger };
        var getIntWithSpec = { |self| self.spec.constrain(self.prValue).asInteger };
        var setFloatWithSpec = { |self, value| self.prValue = self.spec.constrain(value) };
        var getFloatWithSpec = { |self| self.spec.constrain(self.prValue) };
        var setWithoutSpec = { |self, value| self.prValue = value };
        var getWithoutSpec = { |self| self.prValue };

        i_parameters = (
            cv_parameterNames[\triggerCount]: (prValue: 1, spec: ControlSpec(1, c_maxTriggerCount, step: 1), setter: setIntWithSpec, getter: getIntWithSpec),
            cv_parameterNames[\triggers]: (prValue: [], setter: setWithoutSpec, getter: getWithoutSpec),
            cv_parameterNames[\bpm]: (prValue: 1, spec: ControlSpec(1, 240, step: 1), setter: setIntWithSpec, getter: getIntWithSpec),
            cv_parameterNames[\loopLength]: (prValue: 1, setter: { |self, value| self.prValue = value.max(0.001)}, getter: getWithoutSpec),
            cv_parameterNames[\scaleIndex]: (prValue: 0, spec: ControlSpec(0, cv_scales.size - 1, step: 1), setter: setIntWithSpec, getter: getIntWithSpec),
            cv_parameterNames[\rootIndex]: (prValue: 0, spec: ControlSpec(0, cv_rootLabels.size - 1, step: 1), setter: setIntWithSpec, getter: getIntWithSpec),
            cv_parameterNames[\octaveOffset]: (prValue: 0, spec: ControlSpec(-2, 2, step: 1), setter: setIntWithSpec, getter: getIntWithSpec),
            cv_parameterNames[\globalOffset]: (prValue: 0, setter: setWithoutSpec, getter: getWithoutSpec),
            cv_parameterNames[\quantizedOffsetIndex]: (prValue: 0, spec: ControlSpec(0, cv_quantizedValues.size - 1, step: 1), setter: setIntWithSpec, getter: getIntWithSpec),
            cv_parameterNames[\fineOffset]: (prValue: 0, setter: setWithoutSpec, getter: getWithoutSpec),
            cv_parameterNames[\fineOffsetJitterAmount]: (prValue: 0, setter: setWithoutSpec, getter: getWithoutSpec),
            cv_parameterNames[\fineOffsetJitterFrequency]: (prValue: 0, setter: setWithoutSpec, getter: getWithoutSpec),
            cv_parameterNames[\speedOffset]: (prValue: 0, setter: setWithoutSpec, getter: getWithoutSpec),
            cv_parameterNames[\speedOffsetJitterAmount]: (prValue: 0, setter: setWithoutSpec, getter: getWithoutSpec),
            cv_parameterNames[\speedOffsetJitterFrequency]: (prValue: 0, setter: setWithoutSpec, getter: getWithoutSpec),
            cv_parameterNames[\activePointCount]: (prValue: 0, spec: ControlSpec(1, c_maxPointCount, step: 1), setter: setIntWithSpec, getter: getIntWithSpec),
            cv_parameterNames[\probability]: (prValue: 0, spec: ControlSpec(0.0, 1.0, step: 0.001), setter: setFloatWithSpec, getter: getFloatWithSpec),
            cv_parameterNames[\lowNote]: (prValue: 0, spec: ControlSpec(0, 127, step: 1), setter: setIntWithSpec, getter: getIntWithSpec),
            cv_parameterNames[\highNote]: (prValue: 0, spec: ControlSpec(0, 127, step: 1), setter: setIntWithSpec, getter: getIntWithSpec),
            cv_parameterNames[\paused]: (prValue: false, setter: setWithoutSpec, getter: getWithoutSpec)
        );

        this.prDebugPrint("Done initializing parameters");
    }

    setParameterValues {
        |triggerCount=8, triggers=#[true], bpm=110, loopLength=1, scaleIndex=0, root=0, octaveOffset=0, globalOffset=0,
        quantizedOffset=0, fineOffset=0, fineOffsetJitterAmount=0, fineOffsetJitterFrequency=1, speedOffset=0,
        speedOffsetJitterAmount=0, speedOffsetJitterFrequency=1, activePointCount=8, probability=1, lowNote=0,
        highNote=127, paused=false|
        this.triggerCount_(triggerCount);
        this.triggers_(triggers);
        this.bpm_(bpm);
        this.loopLength_(loopLength);
        this.scaleIndex_(scaleIndex);
        this.root_(root);
        this.octaveOffset_(octaveOffset);
        this.globalOffset_(globalOffset);
        this.quantizedOffset_(quantizedOffset);
        this.fineOffset_(fineOffset);
        this.fineOffsetJitterAmount_(fineOffsetJitterAmount);
        this.fineOffsetJitterFrequency_(fineOffsetJitterFrequency);
        this.speedOffset_(speedOffset);
        this.speedOffsetJitterAmount_(speedOffsetJitterAmount);
        this.speedOffsetJitterFrequency_(speedOffsetJitterFrequency);
        this.activePointCount_(activePointCount);
        this.probability_(probability);
        this.lowNote_(lowNote);
        this.highNote_(highNote);
        this.paused_(paused);
        this.prDebugPrint("Done setting parameter values");
    }

    loadPreset { |key|
        // TODO Implement (load preset from given key)
    }

    presetKeys {
        // TODO Implement (return keys of preset dict)
    }

    /** Initialize points synth */
    prInitializePointsSynth {
        i_pointsBus = Bus.control(i_server, c_maxPointCount);

        i_server.bind {
            SynthDef(\points, {
                var globalOffset = \globalOffset.kr(0);
                var bpm = \bpm.kr(60);
                var refreshRate = \refreshRate.kr(1);
                var t_reset = \reset.tr(0);
                var loopLength = \loopLength.kr(1);
                var quantizedOffsets = \quantizedOffsets.kr(Array.fill(c_maxPointCount, 0));
                var fineOffsets = \fineOffsets.kr(Array.fill(c_maxPointCount, 0));
                var fineOffsetJitterAmount = \fineOffsetJitterAmount.kr(0);
                var fineOffsetJitterFrequency = \fineOffsetJitterFrequency.kr(1);
                var speedOffsets = \speedOffsets.kr(Array.fill(c_maxPointCount, 0));
                var speedOffsetJitterAmount = \speedOffsetJitterAmount.kr(0);
                var speedOffsetJitterFrequency = \speedOffsetJitterFrequency.kr(1);
                var paused = \paused.kr(0);

                var freq = (bpm/60) * 0.25 / loopLength; // Assuming 4 beats per bar
                var rate = (freq + (speedOffsets + (LFNoise1.kr(speedOffsetJitterFrequency!c_maxPointCount) * speedOffsetJitterAmount))) * ControlDur.ir * (1 - paused);
                var sig = Phasor.kr(t_reset, rate) + quantizedOffsets + (fineOffsets + (LFNoise1.kr(fineOffsetJitterFrequency!c_maxPointCount) * fineOffsetJitterAmount)) + globalOffset % 1;

                SendReply.kr(Impulse.kr(refreshRate), c_uiUpdateOscPath, sig);
                Out.kr(i_pointsBus, sig - rate);
            }).send(i_server);
            i_server.sync;

            i_pointsSynth = Synth.head(i_server, \points);
        };

        this.prDebugPrint("Points Synth initialized");
    }

    /** Recreate all trigger synths */
    prRefreshTriggerSynths {
        var activePointCount = this.activePointCount;
        var synthName = ("trigger"++activePointCount).asSymbol;

        if (activePointCount < 1) {
            ^nil;
        };

        // NOTE: All the code below is in a fork so the SynthDef and Synth creation can be made synchronous for the
        // sequence of event logic to be respected (this matters when the active point count is high).
        fork {
            // TODO use SynthDef.wrap to encapsulate duplicate code in SynthDefs below
            if (activePointCount == 1)
            {
                SynthDef(synthName, {
                    var note = i_notesBus.kr(1);
                    var point = i_pointsBus.kr(1); // Doesn't work for 1 element (doesn't return an array)
                    // NOTE: Use Changed and PulseCount to avoid all points triggering when SynthDef is created
                    var trigger = Changed.kr(PulseCount.kr(point - \offset.kr(0)));
                    var prob = TRand.kr(trig: trigger) <= \probability.kr(1);
                    SendReply.kr(trigger*prob, c_updatePointsTriggeredStateOscPath, [0, note]);
                }).add;
            } {
                SynthDef(synthName, {
                    var notes = i_notesBus.kr(activePointCount);
                    var points = i_pointsBus.kr(activePointCount);
                    points.do { |point, i|
                        // NOTE: Use Changed and PulseCount to avoid all points triggering when SynthDef is created
                        var trigger = Changed.kr(PulseCount.kr(point - \offset.kr(0)));
                        var prob = TRand.kr(trig: trigger) <= \probability.kr(1);
                        SendReply.kr(trigger*prob, c_updatePointsTriggeredStateOscPath, [i, notes[i]]);
                    };
                }).add;
            };
            // Wait for the SynthDef to be created and loaded
            i_server.sync;

            // Update synths to be cleared
            i_synthsToClear = i_synthsToClear.addAll(i_currentTriggerSynths);
            i_currentTriggerSynths = Array.new(c_maxTriggerCount);

            this.triggers.do { |trig, i|
                if (trig) { i_currentTriggerSynths= i_currentTriggerSynths.add(Synth.tail(
                    i_server, synthName, [probability: this.probability, offset: i/this.triggerCount]
                )) };
            };

            // Register all trigger synths (used for clearing synths later)
            i_currentTriggerSynths.do { |synth| NodeWatcher.register(synth) };

            this.prDebugPrint(i_currentTriggerSynths.size+"trigger synth"+synthName.asString+"added");

            // Start clearing routine
            if (i_synthClearRoutine.isPlaying.not) {
                i_synthClearRoutine.reset.play;
            };

            this.prDebugPrint("Trigger synths refreshed");
        }
    }

    registerPointTriggerAction { |action|
        i_oscFuncs = i_oscFuncs.add(OSCFunc({ |msg|
            var pointIdx = msg[3];
            var note = msg[4];
            action.(pointIdx, note);
        }, c_updatePointsTriggeredStateOscPath));

        this.prDebugPrint("Action registered");
    }

    /** Set parameter value and update UI if necessary
    * @param parameterName: the name of the parameter (see cv_parameterNames)
    * @param value: the value to set
    */
    prSetParameterValue { |parameterName, value|
        i_parameters[parameterName].setter(value);
        if (i_parameterUiControls.notNil) {
            // NOTE: SC doesn't bail out early when chaining conditions so we need to split the check in two to avoid 
            // errors if i_parameterUiControls is nil
            if (i_parameterUiControls[parameterName].notNil) {
                var value = i_parameters[parameterName].getter();
                defer { i_parameterUiControls[parameterName].view.value_(value); }
            }
        }
    }

    triggerCount { ^i_parameters[cv_parameterNames.triggerCount].getter() }
    triggerCount_ { |value|
        this.prSetParameterValue(cv_parameterNames.triggerCount, value);
        this.triggers_(this.triggers); // update triggers array
    }

    triggers { ^i_parameters[cv_parameterNames.triggers].getter() }
    triggers_ { |value|
        var padded = value.clipExtend(value.size.min(this.triggerCount));
        padded = padded ++ Array.fill(this.triggerCount - padded.size, false);
        this.prSetParameterValue(cv_parameterNames.triggers, padded);
        this.prRefreshTriggerSynths();
        defer { this.prCreateTriggerControls() };
    }

    bpm { ^i_parameters[cv_parameterNames.bpm].getter() }
    bpm_ { |value|
        this.prSetParameterValue(cv_parameterNames.bpm, value);
        i_server.bind { i_pointsSynth.set(\bpm, this.bpm) };
    }

    loopLength { ^i_parameters[cv_parameterNames.loopLength].getter() }
    loopLength_ { |value|
        this.prSetParameterValue(cv_parameterNames.loopLength, value);
        i_server.bind { i_pointsSynth.set(\loopLength, this.loopLength) };
    }

    scaleIndex { ^i_parameters[cv_parameterNames.scaleIndex].getter() }
    scaleIndex_ { |value|
        this.prSetParameterValue(cv_parameterNames.scaleIndex, value);
        this.prUpdateNotesBus();
    }

    root { ^i_parameters[cv_parameterNames.rootIndex].getter() }
    root_ { |value|
        this.prSetParameterValue(cv_parameterNames.rootIndex, value);
        this.prUpdateNotesBus();
    }

    octaveOffset { ^i_parameters[cv_parameterNames.octaveOffset].getter() }
    octaveOffset_ { |value|
        this.prSetParameterValue(cv_parameterNames.octaveOffset, value);
        this.prUpdateNotesBus();
    }

    globalOffset { ^i_parameters[cv_parameterNames.globalOffset].getter() }
    globalOffset_ { |value|
        this.prSetParameterValue(cv_parameterNames.globalOffset, value);
        i_server.bind{ i_pointsSynth.set(\globalOffset, this.globalOffset) };
    }

    quantizedOffset { ^i_parameters[cv_parameterNames.quantizedOffsetIndex].getter() }
    quantizedOffset_ { |value|
        this.prSetParameterValue(cv_parameterNames.quantizedOffsetIndex, value);
        i_server.bind{ i_pointsSynth.set(\quantizedOffsets, Array.series(c_maxPointCount, step: cv_quantizedValues[this.quantizedOffset])) };
    }

    fineOffset { ^i_parameters[cv_parameterNames.fineOffset].getter() }
    fineOffset_ { |value|
        this.prSetParameterValue(cv_parameterNames.fineOffset, value);
        i_server.bind{ i_pointsSynth.set(\fineOffsets, Array.series(c_maxPointCount, step: this.fineOffset * 0.05)) };
    }

    fineOffsetJitterAmount { ^i_parameters[cv_parameterNames.fineOffsetJitterAmount].getter() }
    fineOffsetJitterAmount_ { |value|
        this.prSetParameterValue(cv_parameterNames.fineOffsetJitterAmount, value);
        i_server.bind{ i_pointsSynth.set(\fineOffsetJitterAmount, this.fineOffsetJitterAmount * 0.05) };
    }

    fineOffsetJitterFrequency { ^i_parameters[cv_parameterNames.fineOffsetJitterFrequency].getter() }
    fineOffsetJitterFrequency_ { |value|
        this.prSetParameterValue(cv_parameterNames.fineOffsetJitterFrequency, value);
        i_server.bind{ i_pointsSynth.set(\fineOffsetJitterFrequency, this.fineOffsetJitterFrequency) };
    }

    speedOffset { ^i_parameters[cv_parameterNames.speedOffset].getter() }
    speedOffset_ { |value|
        this.prSetParameterValue(cv_parameterNames.speedOffset, value);
        i_server.bind{ i_pointsSynth.set(\speedOffsets, Array.series(c_maxPointCount, step: this.speedOffset * 0.01)) };
    }

    speedOffsetJitterAmount { ^i_parameters[cv_parameterNames.speedOffsetJitterAmount].getter() }
    speedOffsetJitterAmount_ { |value|
        this.prSetParameterValue(cv_parameterNames.speedOffsetJitterAmount, value);
        i_server.bind{ i_pointsSynth.set(\speedOffsetJitterAmount, this.speedOffsetJitterAmount * 0.01) };
    }

    speedOffsetJitterFrequency { ^i_parameters[cv_parameterNames.speedOffsetJitterFrequency].getter() }
    speedOffsetJitterFrequency_ { |value|
        this.prSetParameterValue(cv_parameterNames.speedOffsetJitterFrequency, value);
        i_server.bind{ i_pointsSynth.set(\speedOffsetJitterFrequency, this.speedOffsetJitterFrequency) };
    }

    activePointCount { ^i_parameters[cv_parameterNames.activePointCount].getter() }
    activePointCount_ { |value|
        this.prSetParameterValue(cv_parameterNames.activePointCount, value);
        this.prUpdateNotesBus();
        this.prRefreshTriggerSynths();
    }

    probability { ^i_parameters[cv_parameterNames.probability].getter() }
    probability_ { |value|
        this.prSetParameterValue(cv_parameterNames.probability, value);
        i_server.bind { i_currentTriggerSynths.do { |synth| synth.set(\probability, this.probability) } };
    }

    lowNote { ^i_parameters[cv_parameterNames.lowNote].getter() }
    lowNote_ { |value|
        this.prSetParameterValue(cv_parameterNames.lowNote, value);
        this.prUpdateNotesBus();
    }

    highNote { ^i_parameters[cv_parameterNames.highNote].getter() }
    highNote_ { |value|
        this.prSetParameterValue(cv_parameterNames.highNote, value);
        this.prUpdateNotesBus();
    }

    paused { ^i_parameters[cv_parameterNames.paused].getter() }
    paused_ { |value|
        this.prSetParameterValue(cv_parameterNames.paused, value);
        i_server.bind { i_pointsSynth.set(\paused, if (this.paused, 1, 0)) };
    }

    /** Update notes bus */
    prUpdateNotesBus{
        var notes = this.activePointCount.collect({ |i|
            var note = this.root + cv_scales[this.scaleIndex].performDegreeToKey(i);
            note = note.wrap(this.lowNote, this.highNote);
            note = (cv_scales[this.scaleIndex].semitones + this.root).performNearestInScale(note); // Quantize to scale with given root
            note + (12 * this.octaveOffset)
        });
        i_notesBus.setnSynchronous(notes);
    }

    gui { |refreshRate = 30, showAdvancedGUI = false|
        var refreshOscFunc;
        var debounceRoutine;

        i_server.bind { i_pointsSynth.set(\refreshRate, refreshRate) };

        if (i_window.notNil) {
            i_window.front;
            ^nil;
        };

        refreshOscFunc = OSCFunc({|msg|
            i_pointsPos = msg[3..];
            defer { i_userView.refresh };
        }, c_uiUpdateOscPath);

        debounceRoutine = Routine { loop {
            if (i_nextDebounceAction.notNil) {
                i_nextDebounceAction.(); // Run the next function and reset the storage value
                i_nextDebounceAction = nil;
            };
            c_debounceTime.yield;
        }}.play;

        AppClock.sched(0, {
            var checkboxes, layoutRows;

            var width = 400, height = 200;
            var screen = Window.availableBounds;
            i_window = Window.new("Harmony sequencer", Rect((screen.width - width)/2, (screen.height + height)/2, width, height)).onClose_({ refreshOscFunc.free; debounceRoutine.free; });

            i_userView = UserView().background_(Color.white).drawFunc_({|view|
                var width = view.bounds.width;
                var height = view.bounds.height;
                var step = width / this.triggerCount;
                var pointHeightStep = height / (this.activePointCount+1);
                var pointRadius = 3;

                // Draw trigger bars
                Pen.strokeColor_(Color.black);
                this.triggers().do {|v, i|
                    if (v) {
                        var xPos = ((i+0.5)*step);
                        Pen.moveTo(xPos@0);
                        Pen.lineTo(xPos@height);
                    };
                };
                Pen.stroke();

                // Draw points
                this.activePointCount.do { |i|
                    var xPos = (i_pointsPos[i] + (this.triggerCount*2).reciprocal) % 1.0; // Shift xpos to match drawn line offset
                    var yPos = (i+1) * pointHeightStep;
                    Pen.addArc((xPos * width)@yPos, pointRadius, 0, 2pi);
                    Pen.stroke;
                    Pen.addArc((xPos * width)@yPos, pointRadius * i_pointsTriggerState[i] * 2, 0, 2pi);
                    Pen.fill;
                };

                // Reset all points trigger crossing state
                c_maxPointCount.do {|i| i_pointsTriggerState[i] = (i_pointsTriggerState[i] - 0.1).max(0.0);} // Fade over 10 frames
            });

            this.prCreateParametersControls();
            this.prCreateTriggerControls();

            layoutRows = [
                this.prGetControlGridRow(cv_parameterNames.bpm),
                this.prGetControlGridRow(cv_parameterNames.loopLength),
                this.prGetControlGridRow(cv_parameterNames.scaleIndex),
                this.prGetControlGridRow(cv_parameterNames.rootIndex),
                this.prGetControlGridRow(cv_parameterNames.octaveOffset),
                this.prGetControlGridRow(cv_parameterNames.globalOffset),
                this.prGetControlGridRow(cv_parameterNames.quantizedOffsetIndex),
                this.prGetControlGridRow(cv_parameterNames.fineOffset),
                this.prGetControlGridRow(cv_parameterNames.speedOffset),
                [[Button().string_("Reset speed offset phase").mouseDownAction_({ i_server.bind { i_pointsSynth.set(\reset, 1)} }), columns: 2]],
                this.prGetControlGridRow(cv_parameterNames.activePointCount),
                this.prGetControlGridRow(cv_parameterNames.probability),
                this.prGetControlGridRow(cv_parameterNames.triggerCount),
                this.prGetControlGridRow(cv_parameterNames.lowNote),
                this.prGetControlGridRow(cv_parameterNames.highNote),
            ];

            if (showAdvancedGUI) {
                layoutRows = layoutRows.insert(7, this.prGetControlGridRow(cv_parameterNames.fineOffsetJitterAmount)); // After fine offset
                layoutRows = layoutRows.insert(8, this.prGetControlGridRow(cv_parameterNames.fineOffsetJitterFrequency));
                layoutRows = layoutRows.insert(10, this.prGetControlGridRow(cv_parameterNames.speedOffsetJitterAmount)); // After speed offset
                layoutRows = layoutRows.insert(11, this.prGetControlGridRow(cv_parameterNames.speedOffsetJitterFrequency));
            };

            i_window.layout_(VLayout(
                GridLayout.rows(*layoutRows),
                [i_userView.minSize_(0@100), stretch:1],
                i_triggerCheckboxContainer,
                HLayout(Button().string_("Play").mouseDownAction_({ this.paused_(false) }), Button().string_("Stop").mouseDownAction_({ this.paused_(true) }))
                // [Button().string_("Toggle advanced GUI").mouseDownAction_({ showAdvancedGUI = !showAdvancedGUI }), stretch:1], // TODO Make this work by using a view container similar to i_triggerCheckboxContainer
            ));

            i_window.front;
        });
    }

    /** Create UI controls for parameters */
    prCreateParametersControls {
        var toMidiNoteString = {
                var notes = ["C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"];
                { |value| notes[value%12]+((value/12).asInteger - 2); };
        }.();

        // TODO Extract those to a method to avoid repeated code
        var lowNoteControlDragging = false;
        var lowNoteControlLastY = 0;
        var lowNoteControl = NumberBox()
            .string_(toMidiNoteString.(this.lowNote.asInteger))
            .keyDownAction_({|view, char, modifiers, unicode, keycode, key|
                    if (keycode == c_arrowUpKeycode) {this.lowNote_(this.lownote + 1)};
                    if (keycode == c_arrowDownKeycode) {this.lowNote_(this.lowNote - 1)};
                    view.string_(toMidiNoteString.(this.lowNote.asInteger));
            })
            .mouseDownAction_({|view, x, y, modifiers, button, clickCount|
                    lowNoteControlDragging = (button==c_leftMouseButton);
                    lowNoteControlLastY = y;
                    true; // Mark event as processed
            })
            .mouseMoveAction_({|view, x, y, modifiers, button, clickCount|
                    if (lowNoteControlDragging) {
                        var dir = (y - lowNoteControlLastY) / 20;
                        var speed = 1;
                        if (modifiers == c_shiftModifier) { speed = 12 }; // If shift is pressed, jump an octave
                        if (dir > 1) { this.lowNote_(this.lowNote - speed); lowNoteControlLastY = y; };
                        if (dir < -1) { this.lowNote_(this.lowNote + speed); lowNoteControlLastY = y; };
                };
                    view.string_(toMidiNoteString.(this.lowNote.asInteger));
                    true;
            } )
            .mouseUpAction_({|view, x, y, modifiers, button, clickCount|
                    lowNoteControlDragging = false;
                    true;
            });

        var highNoteControlDragging = false;
        var highNoteControlLastY = 0;
        var highNoteControl = NumberBox()
            .string_(toMidiNoteString.(this.highNote.asInteger))
            .keyDownAction_({|view, char, modifiers, unicode, keycode, key|
                    if (keycode == c_arrowUpKeycode) {this.highNote_(this.highnote + 1)};
                    if (keycode == c_arrowDownKeycode) {this.highNote_(this.highNote - 1)};
                    view.string_(toMidiNoteString.(this.highNote.asInteger));
            })
            .mouseDownAction_({|view, x, y, modifiers, button, clickCount|
                    highNoteControlDragging = (button==c_leftMouseButton);
                    highNoteControlLastY = y;
                    true; // Mark event as processed
            })
            .mouseMoveAction_({|view, x, y, modifiers, button, clickCount|
                    if (highNoteControlDragging) {
                        var dir = (y - highNoteControlLastY) / 20;
                        var speed = 1;
                        if (modifiers == c_shiftModifier) { speed = 12 }; // If shift is pressed, jump an octave
                        if (dir > 1) { this.highNote_(this.highNote - speed); highNoteControlLastY = y; };
                        if (dir < -1) { this.highNote_(this.highNote + speed); highNoteControlLastY = y; };
                };
                    view.string_(toMidiNoteString.(this.highNote.asInteger));
                    true;
            } )
            .mouseUpAction_({|view, x, y, modifiers, button, clickCount|
                    highNoteControlDragging = false;
                    true;
            });


        // TODO set bounds and steps using parameters spec?
        i_parameterUiControls = (
            cv_parameterNames[\bpm]: (label: "BPM", view: NumberBox().step_(1).scroll_step_(1).clipLo_(1).value_(this.bpm).action_({|view| this.bpm_(view.value) })),
            cv_parameterNames[\loopLength]: (label: "Loop length", view: NumberBox().step_(1).scroll_step_(1).clipLo_(0.001).value_(this.loopLength).action_({|view| this.loopLength_(view.value) })),
            cv_parameterNames[\scaleIndex]: (label: "Scale", view: PopUpMenu().items_(cv_scaleLabels).value_(this.scaleIndex).action_({|view| this.scaleIndex_(view.value) })),
            cv_parameterNames[\rootIndex]: (label: "Root", view: PopUpMenu().items_(cv_rootLabels).value_(this.root).action_({|view| this.root_(view.value) })),
            cv_parameterNames[\octaveOffset]: (label: "Octave offset", view: NumberBox().step_(1).scroll_step_(1).clipLo_(-2).clipHi_(2).value_(this.octaveOffset).action_({|view| this.octaveOffset_(view.value) })),
            cv_parameterNames[\globalOffset]: (label: "Global offset", view: NumberBox().step_(0.01).scroll_step_(0.01).value_(this.globalOffset).action_({|view| this.globalOffset_(view.value) })),
            cv_parameterNames[\quantizedOffsetIndex]: (label: "Quantized offset", view: PopUpMenu().items_(cv_quantizedLabels).value_(this.quantizedOffset).action_({|view| this.quantizedOffset_(view.value) })),
            cv_parameterNames[\fineOffset]: (label: "Fine offset", view: NumberBox().step_(0.01).scroll_step_(0.01).value_(this.fineOffset).action_({|view| this.fineOffset_(view.value) })),
            cv_parameterNames[\fineOffsetJitterAmount]: (label: "Fine offset jitter amount", view: NumberBox().step_(0.01).scroll_step_(0.01).value_(this.fineOffsetJitterAmount).action_({|view| this.fineOffsetJitterAmount_(view.value) })),
            cv_parameterNames[\fineOffsetJitterFrequency]: (label: "Fine offset jitter frequency", view: NumberBox().step_(0.01).scroll_step_(0.01).value_(this.fineOffsetJitterFrequency).action_({|view| this.fineOffsetJitterFrequency_(view.value) })),
            cv_parameterNames[\speedOffset]: (label: "Speed offset", view: NumberBox().step_(0.01).scroll_step_(0.01).value_(this.speedOffset).action_({|view| this.speedOffset_(view.value) })),
            cv_parameterNames[\speedOffsetJitterAmount]: (label: "Speed offset jitter amount", view: NumberBox().step_(0.01).scroll_step_(0.01).value_(this.speedOffsetJitterAmount).action_({|view| this.speedOffsetJitterAmount_(view.value) })),
            cv_parameterNames[\speedOffsetJitterFrequency]: (label: "Speed offset jitter frequency", view: NumberBox().step_(0.01).scroll_step_(0.01).value_(this.speedOffsetJitterFrequency).action_({|view| this.speedOffsetJitterFrequency_(view.value) })),
            cv_parameterNames[\probability]: (label: "Probability", view: NumberBox().step_(0.01).scroll_step_(0.01).clipLo_(0.0).clipHi_(1.0).value_(this.probability).action_({|view| this.probability_(view.value) })),
            cv_parameterNames[\activePointCount]: (label: "Point count", view: NumberBox().step_(1).scroll_step_(1).clipLo_(1).clipHi_(c_maxPointCount).value_(this.activePointCount).action_({|view| this.activePointCount_(view.value) })),
            cv_parameterNames[\triggerCount]: (label: "Trigger count", view: NumberBox().step_(1).scroll_step_(1).clipLo_(1).clipHi_(c_maxTriggerCount).value_(this.triggerCount).action_({|view| this.triggerCount_(view.value) })),
            cv_parameterNames[\lowNote]: (label: "Low note", view: lowNoteControl),
            cv_parameterNames[\highNote]: (label: "High note", view: highNoteControl),
        );
    }

    /** Create a row of controls for a single parameter
    * @param parameterName: the name of the parameter (see cv_parameterNames)
    * @return [label, view]
    */
    prGetControlGridRow { |parameterName|
        // TODO Add randomize and lock controls
        var uiControl = i_parameterUiControls[parameterName];
        ^[StaticText().string_(uiControl.label), uiControl.view];
    }

    prCreateTriggerControls {
        var checkboxes;

        if (i_triggerCheckboxContainer.isNil) {
            i_triggerCheckboxContainer = View();
        } {
            i_triggerCheckboxContainer.removeAll; // Clear all children
        };

        checkboxes = this.triggerCount.collect {|i| CheckBox().value_(this.triggers[i]).action_({|checkbox|
            var currentTriggers = this.triggers;
            currentTriggers[i] = checkbox.value;
            this.triggers_(currentTriggers);
            i_userView.refresh;
        }) };

        i_triggerCheckboxContainer.layout_(HLayout(*checkboxes.collect {|view| [view, align: \center]}));
    }

    free {
        i_pointsSynth.free;
        i_oscFuncs.do{ |func| func.free };
        i_pointsBus.free;
        i_notesBus.free;
        i_currentTriggerSynths.do {|synth| synth.free };
        this.prDebugPrint("Done freeing");
    }
}
