// TODO
// Implement presets
// Debounce other controls (as they show late message when dragged) - also try to reduce debounce time to 10ms
// prefix instance vars with i_, class vars with c_ and private methods with pr
// Fix points synth to use only named controls
// Once all is fixed, remove prints
HarmonySequencer {
    const maxTriggerCount = 16;
    const maxPointCount = 32; // 64 makes trigger SynthDef too heavy to load (even if putting less code in the do loop) <- try splitting it into multiple synths when size is above 32
    const uiUpdateOscPath = "/pointPosUpdate";
    const updatePointsTriggeredStateOscPath = "/triggerCrossing";
    const c_debounceTime = 0.1;

    classvar quantizedValues;
    classvar quantizedLabels;
    classvar scales;
    classvar scaleLabels;
    classvar rootLabels;
    classvar presets;

    var i_window;
    var i_userView;
    var i_triggerCheckboxContainer = nil;
    var server;
    var pointsPos;
    var pointsTriggerState;
    var oscFuncs;
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

    *new { |server|
        // Initialize class vars
        quantizedValues = [0, 1/16, 1/8, 1/4, 1/2, 1.5/16, 1.5/8, 1.5/4, 1.5/2];
        quantizedLabels = ["None", "16th", "8th", "Quarter", "Half", "Dotted 16th", "Dotted 8", "Dotted quarter", "Dotted half"];
        scales = [Scale.chromatic, Scale.majorPentatonic, Scale.minorPentatonic, Scale.ionian, Scale.dorian, Scale.phrygian, Scale.lydian, Scale.mixolydian, Scale.aeolian, Scale.locrian];
        scaleLabels = ["chromatic", "minorPentatonic", "majorPentatonic", "ionian", "dorian", "phrygian", "lydian", "mixolydian", "aeolian", "locrian"];
        rootLabels = ["C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"];
        presets = Dictionary.newFrom([
            default: [8, [true, false, false, false, false, false, false, false], ]
        ]);

        ^super.new.init(server);
    }

    init {
        |appServer|

        server = appServer;
        oscFuncs = Array.new();
        pointsPos = Array.fill(maxPointCount, 0);
        pointsTriggerState = Array.fill(maxPointCount, 0);

        i_synthClearRoutine = Routine({
            while ({i_synthsToClear.size > 0}) {
                var removed = Array.new();
                server.bind {
                    i_synthsToClear.do { |synth, i| if (synth.isRunning) {
                        synth.free;
                        removed = removed.add(i);
                    } };
                    removed.reverseDo { |index| i_synthsToClear.removeAt(index) };
                };
                ("Cleared"+removed.size+"synth,"+i_synthsToClear.size+"left").postln;
                server.latency.yield;
            };
            "Done clearing".postln;
        });

        this.prSynthInit();
        this.registerPointTriggerAction({ |pointIdx, note|
            pointsTriggerState[pointIdx] = 1.0;
        });
        this.prInitState();
        this.initializeValues();

        "Done initializing".postln;
    }

    prInitState {
        // NOTE: Can use `value` and `isInteger` as those are already reserved by supercollider
        i_triggerCount = (val: 1, spec: ControlSpec(1, maxTriggerCount, step: 1), isInt: true);
        i_triggers = (val: []);
        i_bpm = (val: 1, spec: ControlSpec(1, 240, step: 1), isInt: true);
        i_scaleIdx = (val: 0, spec: ControlSpec(0, scales.size - 1, step: 1), isInt: true);
        i_root = (val: 0, spec: ControlSpec(0, rootLabels.size - 1, step: 1), isInt: true);
        i_octave = (val: 0, spec: ControlSpec(0, 10, step: 1), isInt: true);
        i_globalOffset = (val: 0);
        i_quantizedOffset = (val: 0, spec: ControlSpec(0, quantizedValues.size - 1, step: 1), isInt: true);
        i_fineOffset = (val: 0);
        i_speedOffset = (val: 0);
        i_activePointCount = (val: 1, spec: ControlSpec(1, maxPointCount, step: 1), isInt: true);
        i_probability = (val: 0, spec: ControlSpec(0.0, 1.0, step: 0.001));
    }

    prUpdateControlValue { |valueEvent, newValue|
        if (valueEvent.spec.isNil) { 
            valueEvent.val = newValue 
        } {
            valueEvent.val = valueEvent.spec.unmap(newValue);
        }
    }

    prGetControlValue { |valueEvent|
        var value;
        if (valueEvent.spec.isNil) { ^valueEvent.val };
        value = valueEvent.spec.map(valueEvent.val);
        if (valueEvent.isInt.notNil && valueEvent.isInt) { ^value.asInteger; };
        ^value;
    }

    initializeValues {
        this.triggerCount_(8);
        this.triggers_([true]);
        this.bpm_(110);
        this.scaleIndex_(0);
        this.root_(0);
        this.octave_(4);
        this.globalOffset_(0);
        this.quantizedOffset_(0);
        this.fineOffset_(0);
        this.speedOffset_(0);
        this.activePointCount_(8);
        this.probability_(1);
    }

    setValues {
        |triggerCount, triggers, bpm, scaleIndex, root, octave|
        this.triggerCount_(triggerCount);
        this.triggers_(triggers);
        this.bpm_(bpm);
        this.scaleIndex_(scaleIndex);
        this.root_(root);
        this.octave_(octave);
        // TODO Add missing values
    }

    loadPreset { |key|
        // TODO Implement (load preset from given key)
    }

    presetKeys {
        // TODO Implement (return keys of preset dict)
    }

    prSynthInit {
        i_pointsBus = Bus.control(server, maxPointCount);

        server.bind {
            SynthDef(\points, {
                |globalOffset=0, bpm=60, refreshRate=1, t_reset=0|

                var quantizedOffsets = \quantizedOffsets.kr(Array.fill(maxPointCount, 0));
                var fineOffsets = \fineOffsets.kr(Array.fill(maxPointCount, 0));
                var speedOffsets = \speedOffsets.kr(Array.fill(maxPointCount, 0));

                var freq = (bpm/60) * 0.25; // Assuming 4 beats per bar
                var rate = (freq + speedOffsets) * ControlDur.ir;
                var sig = Phasor.kr(t_reset, rate) + quantizedOffsets + fineOffsets + globalOffset % 1;

                SendReply.kr(Impulse.kr(refreshRate), uiUpdateOscPath, sig);
                Out.kr(i_pointsBus, sig - rate);
            }).send(server);
            server.sync;

            i_pointsSynth = Synth.head(server, \points);
        };

        "Synth initialized".postln;
    }

    /** Create a new trigger synth
    * offset: normalized trigger position (e.g., 0 for first, 3/8 for third of eight)
    */
    prAddTriggerSynth { |offset|
        var synth, synthDef;
        var activePointCount = this.activePointCount;
        var synthName = ("trigger"++activePointCount).asSymbol;
        var notes = this.prGetNotes();

        if (activePointCount < 1) {
            ^nil;
        };

        // TODO use SynthDef.wrap to encapsulate duplicate code in SynthDefs below
        if (activePointCount == 1)
        {
            synthDef = SynthDef(synthName, {
                var note = \notes.kr(48);
                var point = i_pointsBus.kr(1); // Doesn't work for 1 element (doesn't return an array)
                var trigger = Changed.kr(PulseCount.kr(point - \offset.kr(0)));
                var prob = TRand.kr(trig: trigger) <= \probability.kr(1);
                SendReply.kr(trigger*prob, updatePointsTriggeredStateOscPath, [0, note]);
            });
        } {
            synthDef = SynthDef(synthName, {
                var notes = \notes.kr(Array.series(activePointCount, 48, 1));
                var points = i_pointsBus.kr(activePointCount);
                points.do { |point, i|
                    var trigger = Changed.kr(PulseCount.kr(point - \offset.kr(0)));
                    var prob = TRand.kr(trig: trigger) <= \probability.kr(1);
                    SendReply.kr(trigger*prob, updatePointsTriggeredStateOscPath, [i, notes[i]]);
                };
            });
        };
        synth = synthDef.play(server, [probability: this.probability, notes: notes, offset: offset], addAction: \addToTail);
        NodeWatcher.register(synth);

        i_currentTriggerSynths = i_currentTriggerSynths.add(synth);
    }

    /** Recreate all trigger synths */
    prRefreshTriggerSynths {
        // Update synths to be cleared
        i_synthsToClear = i_synthsToClear.addAll(i_currentTriggerSynths);
        i_currentTriggerSynths = Array.new(maxTriggerCount);

        // Create new synth
        this.triggers.do {|trig, i|
            if (trig) { this.prAddTriggerSynth(i/this.triggerCount) };
        };

        // Start clearing routine
        if (i_synthClearRoutine.isPlaying.not) {
            i_synthClearRoutine.reset.play;
        }
    }

    registerPointTriggerAction { |action|
        oscFuncs = oscFuncs.add(OSCFunc({ |msg|
            var pointIdx = msg[3];
            var note = msg[4];
            action.(pointIdx, note);
        }, updatePointsTriggeredStateOscPath));

        "Action registered".postln;
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
        server.bind { i_pointsSynth.set(\bpm, this.bpm) };
    }

    scaleIndex { ^this.prGetControlValue(i_scaleIdx) }
    scaleIndex_ { |value|
        this.prUpdateControlValue(i_scaleIdx, value);
        server.bind { i_currentTriggerSynths.do { |synth| synth.set(\notes, this.prGetNotes) } };
    }

    root { ^this.prGetControlValue(i_root) }
    root_ { |value|
        this.prUpdateControlValue(i_root, value);
        server.bind { i_currentTriggerSynths.do { |synth| synth.set(\notes, this.prGetNotes) } };
    }

    octave { ^this.prGetControlValue(i_octave) }
    octave_ { |value|
        this.prUpdateControlValue(i_octave, value);
        server.bind { i_currentTriggerSynths.do { |synth| synth.set(\notes, this.prGetNotes) } };
    }

    globalOffset { ^this.prGetControlValue(i_globalOffset) }
    globalOffset_ { |value|
        this.prUpdateControlValue(i_globalOffset, value);
        server.bind{ i_pointsSynth.set(\globalOffset, this.globalOffset) };
    }

    quantizedOffset { ^this.prGetControlValue(i_quantizedOffset) }
    quantizedOffset_ { |value|
        this.prUpdateControlValue(i_quantizedOffset, value);
        server.bind{ i_pointsSynth.set(\quantizedOffsets, Array.series(maxPointCount, step: quantizedValues[this.quantizedOffset])) };
    }

    fineOffset { ^this.prGetControlValue(i_fineOffset) }
    fineOffset_ { |value|
        this.prUpdateControlValue(i_fineOffset, value);
        server.bind{ i_pointsSynth.set(\fineOffsets, Array.series(maxPointCount, step: this.fineOffset * 0.1)) };
    }

    speedOffset { ^this.prGetControlValue(i_speedOffset) }
    speedOffset_ { |value|
        this.prUpdateControlValue(i_speedOffset, value);
        server.bind{ i_pointsSynth.set(\speedOffsets, Array.series(maxPointCount, step: this.speedOffset * 0.1)) };
    }

    activePointCount { ^this.prGetControlValue(i_activePointCount) }
    activePointCount_ { |value|
        this.prUpdateControlValue(i_activePointCount, value);
        this.prRefreshTriggerSynths();
    }

    probability { ^this.prGetControlValue(i_probability) }
    probability_ { |value|
        this.prUpdateControlValue(i_probability, value);
        server.bind { i_currentTriggerSynths.do { |synth| synth.set(\probability, this.probability) } };
    }

    prGetNotes{
        ^this.activePointCount.collect({ |i| this.root + (12 * this.octave) + scales[this.scaleIndex].performDegreeToKey(i)})
    }

    gui { |refreshRate = 30|
        var refreshOscFunc;
        var debounceRoutine;

        server.bind { i_pointsSynth.set(\refreshRate, refreshRate) };

        if (i_window.notNil) {
            i_window.front;
            ^nil;
        };

        refreshOscFunc = OSCFunc({|msg|
            pointsPos = msg[3..];
            defer { i_userView.refresh };
        }, uiUpdateOscPath);

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
                var pointHeightStep = height / (this.activePointCount()+1);
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
                this.activePointCount().do { |i|
                    var xPos = (pointsPos[i] + (this.triggerCount*2).reciprocal) % 1.0; // Shift xpos to match drawn line offset
                    var yPos = (i+1) * pointHeightStep;
                    Pen.addArc((xPos * width)@yPos, pointRadius, 0, 2pi);
                    Pen.stroke;
                    Pen.addArc((xPos * width)@yPos, pointRadius * pointsTriggerState[i] * 2, 0, 2pi);
                    Pen.fill;
                };

                // Reset all points trigger crossing state
                maxPointCount.do {|i| pointsTriggerState[i] = (pointsTriggerState[i] - 0.1).max(0.0);} // Fade over 10 frames
            });

            this.prCreateTriggerControls();

            i_window.layout_(VLayout(
                GridLayout.rows(
                    [StaticText().string_("BPM"), NumberBox().step_(1).clipLo_(1).value_(this.bpm()).action_({|view| this.bpm_(view.value) })],
                    [StaticText().string_("Scale"), PopUpMenu().items_(scaleLabels).value_(this.scaleIndex()).action_({|view| this.scaleIndex_(view.value) })],
                    [StaticText().string_("Root"), PopUpMenu().items_(rootLabels).value_(this.root()).action_({|view| this.root_(view.value) })],
                    [StaticText().string_("Octave"), NumberBox().step_(1).scroll_step_(1).clipLo_(0).clipHi_(8).value_(this.octave()).action_({|view| this.octave_(view.value) })],
                    [StaticText().string_("Global offset"), NumberBox().step_(0.01).scroll_step_(0.01).value_(this.globalOffset()).action_({|view| this.globalOffset_(view.value)})],
                    [StaticText().string_("Quantized offset"), PopUpMenu().items_(quantizedLabels).value_(this.quantizedOffset()).action_({|view| this.quantizedOffset_(view.value) })],
                    [StaticText().string_("Fine offset"), NumberBox().step_(0.01).scroll_step_(0.01).value_(this.fineOffset()).action_({|view| this.fineOffset_(view.value) })],
                    [StaticText().string_("Speed offset"), NumberBox().step_(0.01).scroll_step_(0.01).value_(this.speedOffset()).action_({|view| this.speedOffset_(view.value) })],
                    [[Button().string_("Reset speed offset phase").mouseDownAction_({ server.bind { i_pointsSynth.set(\t_reset, 1)} }), columns: 2]],
                    [StaticText().string_("Probability"), NumberBox().step_(0.01).scroll_step_(0.01).clipLo_(0.0).clipHi_(1.0).value_(this.probability()).action_({|view| this.probability_(view.value) })],
                    [StaticText().string_("Point count"), NumberBox().step_(1).scroll_step_(1).clipLo_(1).clipHi_(maxPointCount).value_(this.activePointCount()).action_({|view| var value = view.value; i_nextDebounceAction = { this.activePointCount_(value) }; })],
                    [StaticText().string_("Trigger count"), NumberBox().step_(1).scroll_step_(1).clipLo_(1).clipHi_(maxTriggerCount).value_(this.triggerCount()).action_({|view| var value = view.value; i_nextDebounceAction = { this.triggerCount_(value) }; })],
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
        oscFuncs.do{ |func| func.free };
        i_pointsBus.free;
        i_currentTriggerSynths.do {|synth| synth.free };
        "Done freeing".postln;
    }
}
