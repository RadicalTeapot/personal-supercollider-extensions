// TODO
// Add velocity (ramp up, ramp down, set value, randomize) output with note value in OSC event
// Add randomize and lock controls for most parameters
// Add notes from chords controls
// Add control to rotate note assignment to points
// Implement presets
// Write docs
HarmonySequencer {
    const c_maxTriggerCount = 16;
    const c_maxPointCount = 64;
    const c_uiUpdateOscPath = "/pointPosUpdate";
    const c_updatePointsTriggeredStateOscPath = "/triggerCrossing";
    const c_debounceTime = 0.01;
    const c_longDebounceTime = 0.05;
    const c_arrowUpKeycode = 38;
    const c_arrowDownKeycode = 40;
    const c_shiftModifier = 131072;
    const c_leftMouseButton = 0;
    const c_parameterRegisterKey = \set;
    const c_controlParameterRegisterKey = \uiSet;

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
        var getParameterWithIntSpec = { |value, controlSpec, rate=(c_debounceTime)|
            DebouncedObservableParameter(
                initialValue: value,
                set: { |value| controlSpec.constrain(value).asInteger },
                rate: rate
            );
        };

        var triggerCount = getParameterWithIntSpec.(1, ControlSpec(1, c_maxTriggerCount, step: 1), rate: c_longDebounceTime).register(c_parameterRegisterKey, { this.triggers_(this.triggers) });
        var trigger = DebouncedObservableParameter([], set: { |value|
            var padded = value.clipExtend(value.size.min(this.triggerCount));
            padded ++ Array.fill(this.triggerCount - padded.size, false);
        }, rate: c_longDebounceTime).register(c_parameterRegisterKey, {
            this.prRefreshTriggerSynths();
            defer { this.prCreateTriggerControls() };
        });
        var bpm = getParameterWithIntSpec.(1, ControlSpec(1, 240, step: 1)).register(c_parameterRegisterKey, { |value|
            i_server.bind { i_pointsSynth.set(\bpm, value) };
        });
        var loopLength = DebouncedObservableParameter(1, set: { |value| value.max(0.001) }, rate: c_debounceTime).register(c_parameterRegisterKey, { |value|
            i_server.bind { i_pointsSynth.set(\loopLength, value) };
        });
        var scaleIndex = getParameterWithIntSpec.(0, ControlSpec(0, cv_scales.size - 1, step: 1)).register(c_parameterRegisterKey, { this.prUpdateNotesBus() });
        var rootIndex = getParameterWithIntSpec.(0, ControlSpec(0, cv_rootLabels.size - 1, step: 1)).register(c_parameterRegisterKey, { this.prUpdateNotesBus() });
        var octaveOffset = getParameterWithIntSpec.(0, ControlSpec(-2, 2, step: 1)).register(c_parameterRegisterKey, { this.prUpdateNotesBus() });
        var globalOffset = DebouncedObservableParameter(0, rate: c_debounceTime).register(c_parameterRegisterKey, { |value|
            i_server.bind{ i_pointsSynth.set(\globalOffset, value) }
        });
        var quantizedOffsetIndex = getParameterWithIntSpec.(0, ControlSpec(0, cv_quantizedValues.size - 1, step: 1)).register(c_parameterRegisterKey, { |value|
            i_server.bind{ i_pointsSynth.set(\quantizedOffsets, Array.series(c_maxPointCount, step: cv_quantizedValues[value])) }
        });
        var fineOffset = DebouncedObservableParameter(0, rate: c_debounceTime).register(c_parameterRegisterKey, { |value|
            i_server.bind{ i_pointsSynth.set(\fineOffsets, Array.series(c_maxPointCount, step: value * 0.05)) }
        });
        var fineOffsetJitterAmount = DebouncedObservableParameter(0, rate: c_debounceTime).register(c_parameterRegisterKey, { |value|
            i_server.bind{ i_pointsSynth.set(\fineOffsetJitterAmount, value * 0.05) }
        });
        var fineOffsetJitterFrequency = DebouncedObservableParameter(0, rate: c_debounceTime).register(c_parameterRegisterKey, { |value|
            i_server.bind{ i_pointsSynth.set(\fineOffsetJitterFrequency, value) }
        });
        var speedOffset = DebouncedObservableParameter(0, rate: c_debounceTime).register(c_parameterRegisterKey, { |value|
            i_server.bind{ i_pointsSynth.set(\speedOffsets, Array.series(c_maxPointCount, step: value * 0.01)) }
        });
        var speedOffsetJitterAmount = DebouncedObservableParameter(0, rate: c_debounceTime).register(c_parameterRegisterKey, { |value|
            i_server.bind{ i_pointsSynth.set(\speedOffsetJitterAmount, value * 0.01) }
        });
        var speedOffsetJitterFrequency = DebouncedObservableParameter(0, rate: c_debounceTime).register(c_parameterRegisterKey, { |value|
            i_server.bind{ i_pointsSynth.set(\speedOffsetJitterFrequency, value) }
        });
        var activePointCount = getParameterWithIntSpec.(0, ControlSpec(1, c_maxPointCount, step: 1), rate: c_longDebounceTime).register(c_parameterRegisterKey, { this.prUpdateNotesBus(); this.prRefreshTriggerSynths() });
        var probability = { 
            var spec = ControlSpec(0.0, 1.0, step: 0.001); 
            DebouncedObservableParameter(0, set: { |value| spec.constrain(value) }, rate: c_debounceTime)
        }.().register(c_parameterRegisterKey, { |value| 
            i_server.bind { i_currentTriggerSynths.do { |synth| synth.set(\probability, value) } } 
        });
        var lowNote = getParameterWithIntSpec.(0, ControlSpec(0, 127, step: 1)).register(c_parameterRegisterKey, { this.prUpdateNotesBus() });
        var highNote = getParameterWithIntSpec.(0, ControlSpec(0, 127, step: 1)).register(c_parameterRegisterKey, { this.prUpdateNotesBus() });
        var paused = DebouncedObservableParameter(false, rate: c_debounceTime).register(c_parameterRegisterKey, { |value|
          i_server.bind { i_pointsSynth.set(\paused, if (value, 1, 0)) }
        });


        i_parameters = IdentityDictionary.newFrom([
            cv_parameterNames.triggerCount, triggerCount,
            cv_parameterNames.triggers, trigger,
            cv_parameterNames.bpm, bpm,
            cv_parameterNames.loopLength, loopLength,
            cv_parameterNames.scaleIndex, scaleIndex,
            cv_parameterNames.rootIndex, rootIndex,
            cv_parameterNames.octaveOffset, octaveOffset,
            cv_parameterNames.globalOffset, globalOffset,
            cv_parameterNames.quantizedOffsetIndex, quantizedOffsetIndex,
            cv_parameterNames.fineOffset, fineOffset,
            cv_parameterNames.fineOffsetJitterAmount, fineOffsetJitterAmount,
            cv_parameterNames.fineOffsetJitterFrequency, fineOffsetJitterFrequency,
            cv_parameterNames.speedOffset, speedOffset,
            cv_parameterNames.speedOffsetJitterAmount, speedOffsetJitterAmount,
            cv_parameterNames.speedOffsetJitterFrequency, speedOffsetJitterFrequency,
            cv_parameterNames.activePointCount, activePointCount,
            cv_parameterNames.probability, probability,
            cv_parameterNames.lowNote, lowNote,
            cv_parameterNames.highNote, highNote,
            cv_parameterNames.paused, paused,
        ]);

        this.prDebugPrint("Done initializing parameters");
    }

    setParameterValues {
        |triggerCount=8, triggers=#[true], bpm=110, loopLength=1, scaleIndex=0, rootIndex=0, octaveOffset=0, globalOffset=0,
        quantizedOffsetIndex=0, fineOffset=0, fineOffsetJitterAmount=0, fineOffsetJitterFrequency=1, speedOffset=0,
        speedOffsetJitterAmount=0, speedOffsetJitterFrequency=1, activePointCount=8, probability=1, lowNote=0,
        highNote=127, paused=false|
        i_parameters[cv_parameterNames.triggerCount].setWithoutNotify(triggerCount);
        i_parameters[cv_parameterNames.triggers].setWithoutNotify(triggers);
        i_parameters[cv_parameterNames.bpm].setWithoutNotify(bpm);
        i_parameters[cv_parameterNames.loopLength].setWithoutNotify(loopLength);
        i_parameters[cv_parameterNames.scaleIndex].setWithoutNotify(scaleIndex);
        i_parameters[cv_parameterNames.rootIndex].setWithoutNotify(rootIndex);
        i_parameters[cv_parameterNames.octaveOffset].setWithoutNotify(octaveOffset);
        i_parameters[cv_parameterNames.globalOffset].setWithoutNotify(globalOffset);
        i_parameters[cv_parameterNames.quantizedOffsetIndex].setWithoutNotify(quantizedOffsetIndex);
        i_parameters[cv_parameterNames.fineOffset].setWithoutNotify(fineOffset);
        i_parameters[cv_parameterNames.fineOffsetJitterAmount].setWithoutNotify(fineOffsetJitterAmount);
        i_parameters[cv_parameterNames.fineOffsetJitterFrequency].setWithoutNotify(fineOffsetJitterFrequency);
        i_parameters[cv_parameterNames.speedOffset].setWithoutNotify(speedOffset);
        i_parameters[cv_parameterNames.speedOffsetJitterAmount].setWithoutNotify(speedOffsetJitterAmount);
        i_parameters[cv_parameterNames.speedOffsetJitterFrequency].setWithoutNotify(speedOffsetJitterFrequency);
        i_parameters[cv_parameterNames.activePointCount].setWithoutNotify(activePointCount);
        i_parameters[cv_parameterNames.probability].setWithoutNotify(probability);
        i_parameters[cv_parameterNames.lowNote].setWithoutNotify(lowNote);
        i_parameters[cv_parameterNames.highNote].setWithoutNotify(highNote);
        i_parameters[cv_parameterNames.paused].setWithoutNotify(paused);

        i_parameters.do { |param| param.notify };

        cv_parameterNames.prDebugPrint("Done setting parameter values");
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

    triggerCount { ^i_parameters[cv_parameterNames.triggerCount].value }
    triggerCount_ { |value| i_parameters[cv_parameterNames.triggerCount].set(value) }

    triggers { ^i_parameters[cv_parameterNames.triggers].value }
    triggers_ { |value| i_parameters[cv_parameterNames.triggers].set(value) }

    bpm { ^i_parameters[cv_parameterNames.bpm].value }
    bpm_ { |value| i_parameters[cv_parameterNames.bpm].set(value) }

    loopLength { ^i_parameters[cv_parameterNames.loopLength].value }
    loopLength_ { |value| i_parameters[cv_parameterNames.loopLength].set(value) }

    scaleIndex { ^i_parameters[cv_parameterNames.scaleIndex].value }
    scaleIndex_ { |value| i_parameters[cv_parameterNames.scaleIndex].set(value); }

    rootIndex { ^i_parameters[cv_parameterNames.rootIndex].value }
    rootIndex_ { |value| i_parameters[cv_parameterNames.rootIndex].set(value); }

    octaveOffset { ^i_parameters[cv_parameterNames.octaveOffset].value }
    octaveOffset_ { |value| i_parameters[cv_parameterNames.octaveOffset].set(value); }

    globalOffset { ^i_parameters[cv_parameterNames.globalOffset].value }
    globalOffset_ { |value| i_parameters[cv_parameterNames.globalOffset].set(value); }

    quantizedOffsetIndex { ^i_parameters[cv_parameterNames.quantizedOffsetIndex].value }
    quantizedOffsetIndex_ { |value| i_parameters[cv_parameterNames.quantizedOffsetIndex].set(value); }

    fineOffset { ^i_parameters[cv_parameterNames.fineOffset].value }
    fineOffset_ { |value| i_parameters[cv_parameterNames.fineOffset].set(value); }

    fineOffsetJitterAmount { ^i_parameters[cv_parameterNames.fineOffsetJitterAmount].value }
    fineOffsetJitterAmount_ { |value| i_parameters[cv_parameterNames.fineOffsetJitterAmount].set(value); }

    fineOffsetJitterFrequency { ^i_parameters[cv_parameterNames.fineOffsetJitterFrequency].value }
    fineOffsetJitterFrequency_ { |value| i_parameters[cv_parameterNames.fineOffsetJitterFrequency].set(value); }

    speedOffset { ^i_parameters[cv_parameterNames.speedOffset].value }
    speedOffset_ { |value| i_parameters[cv_parameterNames.speedOffset].set(value); }

    speedOffsetJitterAmount { ^i_parameters[cv_parameterNames.speedOffsetJitterAmount].value }
    speedOffsetJitterAmount_ { |value| i_parameters[cv_parameterNames.speedOffsetJitterAmount].set(value); }

    speedOffsetJitterFrequency { ^i_parameters[cv_parameterNames.speedOffsetJitterFrequency].value }
    speedOffsetJitterFrequency_ { |value| i_parameters[cv_parameterNames.speedOffsetJitterFrequency].set(value); }

    activePointCount { ^i_parameters[cv_parameterNames.activePointCount].value }
    activePointCount_ { |value| i_parameters[cv_parameterNames.activePointCount].set(value); }

    probability { ^i_parameters[cv_parameterNames.probability].value }
    probability_ { |value| i_parameters[cv_parameterNames.probability].set(value); }

    lowNote { ^i_parameters[cv_parameterNames.lowNote].value }
    lowNote_ { |value| i_parameters[cv_parameterNames.lowNote].set(value); }

    highNote { ^i_parameters[cv_parameterNames.highNote].value }
    highNote_ { |value| i_parameters[cv_parameterNames.highNote].set(value); }

    paused { ^i_parameters[cv_parameterNames.paused].value }
    paused_ { |value| i_parameters[cv_parameterNames.paused].set(value); }

    /** Update notes bus */
    prUpdateNotesBus{
        var notes = this.activePointCount.collect({ |i|
            // Note root index matches the semitone value so no need to look it up
            var note = this.rootIndex + cv_scales[this.scaleIndex].performDegreeToKey(i);
            note = note.wrap(this.lowNote, this.highNote);
            note = (cv_scales[this.scaleIndex].semitones + this.rootIndex).performNearestInScale(note); // Quantize to scale with given root
            note + (12 * this.octaveOffset)
        });
        i_notesBus.setnSynchronous(notes);
    }

    gui { |refreshRate = 30, showAdvancedGUI = false|
        var refreshOscFunc;

        i_server.bind { i_pointsSynth.set(\refreshRate, refreshRate) };

        if (i_window.notNil) {
            i_window.front;
            ^nil;
        };

        refreshOscFunc = OSCFunc({|msg|
            i_pointsPos = msg[3..];
            defer { i_userView.refresh };
        }, c_uiUpdateOscPath);

        AppClock.sched(0, {
            var checkboxes, layoutRows;

            var width = 400, height = 200;
            var screen = Window.availableBounds;
            i_window = Window.new("Harmony sequencer", Rect((screen.width - width)/2, (screen.height + height)/2, width, height)).onClose_({
                refreshOscFunc.free;
                this.prUnregisterControlsFromParameters();
            });

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
            this.prRegisterControlsWithParameters();

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


        i_parameterUiControls = IdentityDictionary.newFrom([
            cv_parameterNames.bpm, (label: "BPM", view: NumberBox().step_(1).scroll_step_(1).clipLo_(1).value_(this.bpm).action_({ |view| this.bpm_(view.value) })),
            cv_parameterNames.loopLength, (label: "Loop length", view: NumberBox().step_(1).scroll_step_(1).clipLo_(0.001).value_(this.loopLength).action_({ |view| this.loopLength_(view.value) })),
            cv_parameterNames.scaleIndex, (label: "Scale", view: PopUpMenu().items_(cv_scaleLabels).value_(this.scaleIndex).action_({ |view| this.scaleIndex_(view.value) })),
            cv_parameterNames.rootIndex, (label: "RootIndex", view: PopUpMenu().items_(cv_rootLabels).value_(this.rootIndex).action_({ |view| this.rootIndex_(view.value) })),
            cv_parameterNames.octaveOffset, (label: "Octave offset", view: NumberBox().step_(1).scroll_step_(1).clipLo_(-2).clipHi_(2).value_(this.octaveOffset).action_({ |view| this.octaveOffset_(view.value) })),
            cv_parameterNames.globalOffset, (label: "Global offset", view: NumberBox().step_(0.01).scroll_step_(0.01).value_(this.globalOffset).action_({ |view| this.globalOffset_(view.value) })),
            cv_parameterNames.quantizedOffsetIndex, (label: "Quantized offset", view: PopUpMenu().items_(cv_quantizedLabels).value_(this.quantizedOffsetIndex).action_({ |view| this.quantizedOffsetIndex_(view.value) })),
            cv_parameterNames.fineOffset, (label: "Fine offset", view: NumberBox().step_(0.01).scroll_step_(0.01).value_(this.fineOffset).action_({ |view| this.fineOffset_(view.value) })),
            cv_parameterNames.fineOffsetJitterAmount, (label: "Fine offset jitter amount", view: NumberBox().step_(0.01).scroll_step_(0.01).value_(this.fineOffsetJitterAmount).action_({ |view| this.fineOffsetJitterAmount_(view.value) })),
            cv_parameterNames.fineOffsetJitterFrequency, (label: "Fine offset jitter frequency", view: NumberBox().step_(0.01).scroll_step_(0.01).value_(this.fineOffsetJitterFrequency).action_({ |view| this.fineOffsetJitterFrequency_(view.value) })),
            cv_parameterNames.speedOffset, (label: "Speed offset", view: NumberBox().step_(0.01).scroll_step_(0.01).value_(this.speedOffset).action_({ |view| this.speedOffset_(view.value) })),
            cv_parameterNames.speedOffsetJitterAmount, (label: "Speed offset jitter amount", view: NumberBox().step_(0.01).scroll_step_(0.01).value_(this.speedOffsetJitterAmount).action_({ |view| this.speedOffsetJitterAmount_(view.value) })),
            cv_parameterNames.speedOffsetJitterFrequency, (label: "Speed offset jitter frequency", view: NumberBox().step_(0.01).scroll_step_(0.01).value_(this.speedOffsetJitterFrequency).action_({ |view| this.speedOffsetJitterFrequency_(view.value) })),
            cv_parameterNames.probability, (label: "Probability", view: NumberBox().step_(0.01).scroll_step_(0.01).clipLo_(0.0).clipHi_(1.0).value_(this.probability).action_({ |view| this.probability_(view.value) })),
            cv_parameterNames.activePointCount, (label: "Point count", view: NumberBox().step_(1).scroll_step_(1).clipLo_(1).clipHi_(c_maxPointCount).value_(this.activePointCount).action_({ |view| this.activePointCount_(view.value) })),
            cv_parameterNames.triggerCount, (label: "Trigger count", view: NumberBox().step_(1).scroll_step_(1).clipLo_(1).clipHi_(c_maxTriggerCount).value_(this.triggerCount).action_({ |view| this.triggerCount_(view.value) })),
            cv_parameterNames.lowNote, (label: "Low note", view: lowNoteControl),
            cv_parameterNames.highNote, (label: "High note", view: highNoteControl),
        ]);
    }

    /** Register controls to matching parameter update notification */
    prRegisterControlsWithParameters {
        i_parameters.keysValuesDo { |key, parameter|
            var control = i_parameterUiControls[key];
            if (control.notNil) { parameter.register(c_controlParameterRegisterKey, { |value| defer { control.view.value_(value) } }) };
        }
    }

    /** Unregister all controls from parameter update notification */
    prUnregisterControlsFromParameters {
        i_parameters.do { |parameter|
          if (parameter.registeredKeys.includes(c_controlParameterRegisterKey)) { parameter.unregister(c_controlParameterRegisterKey) };
        };
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
        i_parameters.do {|param| param.free };
        this.prDebugPrint("Done freeing");
    }
}
