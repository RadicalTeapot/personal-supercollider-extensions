// TODO
// Implement presets
// Debounce other controls (as they show late message when dragged) - also try to reduce debounce time to 10ms
// Fix points synth to use only named controls
// Write docs
HarmonySequencer {
    const c_maxTriggerCount = 16;
    const c_maxPointCount = 64;
    const c_uiUpdateOscPath = "/pointPosUpdate";
    const c_updatePointsTriggeredStateOscPath = "/triggerCrossing";
    const c_debounceTime = 0.1;

    classvar cv_quantizedValues;
    classvar cv_quantizedLabels;
    classvar cv_scales;
    classvar cv_scaleLabels;
    classvar cv_rootLabels;
    classvar cv_presets;

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
    var i_synthClearRoutine;
    var i_currentTriggerSynths = #[];
    var i_synthsToClear = #[];
    var i_nextDebounceAction = nil;

    var i_triggerCount;
    var i_triggers;
    var i_bpm;
    var i_scaleIdx;
    var i_root;
    var i_octave;
    var i_globalOffset;
    var i_quantizedOffset;
    var i_fineOffset;
    var i_speedOffset;
    var i_activePointCount;
    var i_probability;

    *new { |server, debug=false|
        // Initialize class vars
        cv_quantizedValues = [0, 1/16, 1/8, 1/4, 1/2, 1.5/16, 1.5/8, 1.5/4, 1.5/2];
        cv_quantizedLabels = ["None", "16th", "8th", "Quarter", "Half", "Dotted 16th", "Dotted 8", "Dotted quarter", "Dotted half"];
        cv_scales = [Scale.chromatic, Scale.majorPentatonic, Scale.minorPentatonic, Scale.ionian, Scale.dorian, Scale.phrygian, Scale.lydian, Scale.mixolydian, Scale.aeolian, Scale.locrian];
        cv_scaleLabels = ["chromatic", "minorPentatonic", "majorPentatonic", "ionian", "dorian", "phrygian", "lydian", "mixolydian", "aeolian", "locrian"];
        cv_rootLabels = ["C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"];
        cv_presets = Dictionary.newFrom([
            default: [8, [true, false, false, false, false, false, false, false], ]
        ]);

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

        this.prInitializePointsSynth();
        this.registerPointTriggerAction({ |pointIdx, note|
            i_pointsTriggerState[pointIdx] = 1.0;
        });
        this.prInitializeParameters();
        this.setParameterValues();

        this.prDebugPrint("Done initializing");
    }

    /** Print text if debug mode is on */
    prDebugPrint { |text|
        if (i_debugMode) { text.postln };
    }

    /** Initialize parameters state */
    prInitializeParameters {
        // NOTE: Can't use `value` and `isInteger` as those are already reserved by supercollider
        // NOTE: `val` is the unmapped value (i.e., from 0 to 1), setting it to 0 means the actual value is the spec minimum
        i_triggerCount = (val: 0, spec: ControlSpec(1, c_maxTriggerCount, step: 1), isInt: true);
        i_triggers = (val: []);
        i_bpm = (val: 0, spec: ControlSpec(1, 240, step: 1), isInt: true);
        i_scaleIdx = (val: 0, spec: ControlSpec(0, cv_scales.size - 1, step: 1), isInt: true);
        i_root = (val: 0, spec: ControlSpec(0, cv_rootLabels.size - 1, step: 1), isInt: true);
        i_octave = (val: 0, spec: ControlSpec(0, 10, step: 1), isInt: true);
        i_globalOffset = (val: 0);
        i_quantizedOffset = (val: 0, spec: ControlSpec(0, cv_quantizedValues.size - 1, step: 1), isInt: true);
        i_fineOffset = (val: 0);
        i_speedOffset = (val: 0);
        i_activePointCount = (val: 0, spec: ControlSpec(1, c_maxPointCount, step: 1), isInt: true);
        i_probability = (val: 0, spec: ControlSpec(0.0, 1.0, step: 0.001));

        this.prDebugPrint("Done initializing parameters");
    }

    /** Update internal parameter value
    * valueEvent - parameter to update
    * newValue - new value for parameter
    */
    prUpdateControlValue { |valueEvent, newValue|
        if (valueEvent.spec.isNil) {
            valueEvent.val = newValue;
        } {
            valueEvent.val = valueEvent.spec.unmap(newValue);
        }
    }

    /** Get internal parameter value
    * valueEvent - parameter to get
    */
    prGetControlValue { |valueEvent|
        var value;
        if (valueEvent.spec.isNil) { ^valueEvent.val };
        value = valueEvent.spec.map(valueEvent.val);
        if (valueEvent.isInt.notNil && valueEvent.isInt) { ^value.asInteger; };
        ^value;
    }

    setParameterValues {
        |triggerCount=8, triggers=#[true], bpm=110, scaleIndex=0, root=0, octave=4, globalOffset=0, quantizedOffset=0, fineOffset=0, speedOffset=0, activePointCount=8, probability=1|
        this.triggerCount_(triggerCount);
        this.triggers_(triggers);
        this.bpm_(bpm);
        this.scaleIndex_(scaleIndex);
        this.root_(root);
        this.octave_(octave);
        this.globalOffset_(globalOffset);
        this.quantizedOffset_(quantizedOffset);
        this.fineOffset_(fineOffset);
        this.speedOffset_(speedOffset);
        this.activePointCount_(activePointCount);
        this.probability_(probability);
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
                |globalOffset=0, bpm=60, refreshRate=1, t_reset=0|

                var quantizedOffsets = \quantizedOffsets.kr(Array.fill(c_maxPointCount, 0));
                var fineOffsets = \fineOffsets.kr(Array.fill(c_maxPointCount, 0));
                var speedOffsets = \speedOffsets.kr(Array.fill(c_maxPointCount, 0));

                var freq = (bpm/60) * 0.25; // Assuming 4 beats per bar
                var rate = (freq + speedOffsets) * ControlDur.ir;
                var sig = Phasor.kr(t_reset, rate) + quantizedOffsets + fineOffsets + globalOffset % 1;

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
        var notes = this.prGetNotes();

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
                    var note = \notes.kr(48);
                    var point = i_pointsBus.kr(1); // Doesn't work for 1 element (doesn't return an array)
                    // NOTE: Use Changed and PulseCount to avoid all points triggering when SynthDef is created
                    var trigger = Changed.kr(PulseCount.kr(point - \offset.kr(0)));
                    var prob = TRand.kr(trig: trigger) <= \probability.kr(1);
                    SendReply.kr(trigger*prob, c_updatePointsTriggeredStateOscPath, [0, note]);
                }).add;
            } {
                SynthDef(synthName, {
                    var notes = \notes.kr(Array.series(activePointCount, 48, 1));
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
                    i_server, synthName, [probability: this.probability, notes: notes, offset: i/this.triggerCount]
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

    triggerCount { ^this.prGetControlValue(i_triggerCount) }
    triggerCount_ { |value|
        this.prUpdateControlValue(i_triggerCount, value);
        this.triggers_(this.triggers); // update triggers array
        defer { this.prCreateTriggerControls() }
    }

    triggers { ^this.prGetControlValue(i_triggers) }
    triggers_ { |value|
        var padded = value.clipExtend(value.size.min(this.triggerCount)); // Limit length // TODO <- Broken here
        padded = padded ++ Array.fill(this.triggerCount - padded.size, false);
        this.prUpdateControlValue(i_triggers, padded); // Pad with false
        this.prRefreshTriggerSynths();
    }

    bpm { ^this.prGetControlValue(i_bpm) }
    bpm_ { |value|
        this.prUpdateControlValue(i_bpm, value);
        i_server.bind { i_pointsSynth.set(\bpm, this.bpm) };
    }

    scaleIndex { ^this.prGetControlValue(i_scaleIdx) }
    scaleIndex_ { |value|
        this.prUpdateControlValue(i_scaleIdx, value);
        i_server.bind { i_currentTriggerSynths.do { |synth| synth.set(\notes, this.prGetNotes) } };
    }

    root { ^this.prGetControlValue(i_root) }
    root_ { |value|
        this.prUpdateControlValue(i_root, value);
        i_server.bind { i_currentTriggerSynths.do { |synth| synth.set(\notes, this.prGetNotes) } };
    }

    octave { ^this.prGetControlValue(i_octave) }
    octave_ { |value|
        this.prUpdateControlValue(i_octave, value);
        i_server.bind { i_currentTriggerSynths.do { |synth| synth.set(\notes, this.prGetNotes) } };
    }

    globalOffset { ^this.prGetControlValue(i_globalOffset) }
    globalOffset_ { |value|
        this.prUpdateControlValue(i_globalOffset, value);
        i_server.bind{ i_pointsSynth.set(\globalOffset, this.globalOffset) };
    }

    quantizedOffset { ^this.prGetControlValue(i_quantizedOffset) }
    quantizedOffset_ { |value|
        this.prUpdateControlValue(i_quantizedOffset, value);
        i_server.bind{ i_pointsSynth.set(\quantizedOffsets, Array.series(c_maxPointCount, step: cv_quantizedValues[this.quantizedOffset])) };
    }

    fineOffset { ^this.prGetControlValue(i_fineOffset) }
    fineOffset_ { |value|
        this.prUpdateControlValue(i_fineOffset, value);
        i_server.bind{ i_pointsSynth.set(\fineOffsets, Array.series(c_maxPointCount, step: this.fineOffset * 0.1)) };
    }

    speedOffset { ^this.prGetControlValue(i_speedOffset) }
    speedOffset_ { |value|
        this.prUpdateControlValue(i_speedOffset, value);
        i_server.bind{ i_pointsSynth.set(\speedOffsets, Array.series(c_maxPointCount, step: this.speedOffset * 0.1)) };
    }

    activePointCount { ^this.prGetControlValue(i_activePointCount) }
    activePointCount_ { |value|
        this.prUpdateControlValue(i_activePointCount, value);
        this.prRefreshTriggerSynths();
    }

    probability { ^this.prGetControlValue(i_probability) }
    probability_ { |value|
        this.prUpdateControlValue(i_probability, value);
        i_server.bind { i_currentTriggerSynths.do { |synth| synth.set(\probability, this.probability) } };
    }

    prGetNotes{
        ^this.activePointCount.collect({ |i| this.root + (12 * this.octave) + cv_scales[this.scaleIndex].performDegreeToKey(i)})
    }

    gui { |refreshRate = 30|
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
            var checkboxes;

            var width = 400, height = 200;
            var screen = Window.availableBounds;
            i_window = Window.new("Harmony sequencer", Rect((screen.width - width)/2, (screen.height + height)/2, width, height)).onClose_({ refreshOscFunc.free; debounceRoutine.free; });

            i_userView = UserView().background_(Color.white).drawFunc_({|view|
                var width = view.bounds.width;
                var height = view.bounds.height;
                var step = width / this.triggerCount();
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

            this.prCreateTriggerControls();

            i_window.layout_(VLayout(
                GridLayout.rows(
                    [StaticText().string_("BPM"), NumberBox().step_(1).clipLo_(1).value_(this.bpm()).action_({|view| this.bpm_(view.value) })],
                    [StaticText().string_("Scale"), PopUpMenu().items_(cv_scaleLabels).value_(this.scaleIndex()).action_({|view| this.scaleIndex_(view.value) })],
                    [StaticText().string_("Root"), PopUpMenu().items_(cv_rootLabels).value_(this.root()).action_({|view| this.root_(view.value) })],
                    [StaticText().string_("Octave"), NumberBox().step_(1).scroll_step_(1).clipLo_(0).clipHi_(8).value_(this.octave()).action_({|view| this.octave_(view.value) })],
                    [StaticText().string_("Global offset"), NumberBox().step_(0.01).scroll_step_(0.01).value_(this.globalOffset()).action_({|view| this.globalOffset_(view.value)})],
                    [StaticText().string_("Quantized offset"), PopUpMenu().items_(cv_quantizedLabels).value_(this.quantizedOffset()).action_({|view| this.quantizedOffset_(view.value) })],
                    [StaticText().string_("Fine offset"), NumberBox().step_(0.01).scroll_step_(0.01).value_(this.fineOffset()).action_({|view| this.fineOffset_(view.value) })],
                    [StaticText().string_("Speed offset"), NumberBox().step_(0.01).scroll_step_(0.01).value_(this.speedOffset()).action_({|view| this.speedOffset_(view.value) })],
                    [[Button().string_("Reset speed offset phase").mouseDownAction_({ i_server.bind { i_pointsSynth.set(\t_reset, 1)} }), columns: 2]],
                    [StaticText().string_("Probability"), NumberBox().step_(0.01).scroll_step_(0.01).clipLo_(0.0).clipHi_(1.0).value_(this.probability()).action_({|view| this.probability_(view.value) })],
                    [StaticText().string_("Point count"), NumberBox().step_(1).scroll_step_(1).clipLo_(1).clipHi_(c_maxPointCount).value_(this.activePointCount).action_({|view| var value = view.value; i_nextDebounceAction = { this.activePointCount_(value) }; })],
                    [StaticText().string_("Trigger count"), NumberBox().step_(1).scroll_step_(1).clipLo_(1).clipHi_(c_maxTriggerCount).value_(this.triggerCount()).action_({|view| var value = view.value; i_nextDebounceAction = { this.triggerCount_(value) }; })],
                ),
                [i_userView.minSize_(0@100), stretch:1],
                i_triggerCheckboxContainer,
            ));

            i_window.front;
        });
    }

    prCreateTriggerControls {
        var checkboxes;

        if (i_triggerCheckboxContainer.isNil) {
            i_triggerCheckboxContainer = View();
        } {
            i_triggerCheckboxContainer.removeAll; // Clear all children
        };

        checkboxes = this.triggerCount().collect {|i| CheckBox().value_(this.triggers[i]).action_({|checkbox|
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
        i_currentTriggerSynths.do {|synth| synth.free };
        this.prDebugPrint("Done freeing");
    }
}
