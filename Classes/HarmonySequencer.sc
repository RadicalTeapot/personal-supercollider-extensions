// TODO
// Validate data set with setters and set method (look at ControlSpec)
// Implement presets
// Debounce other controls (as they show late message when dragged) - also try to reduce debounce time to 10ms
// prefix instance vars with i_, class vars with c_ and private methods with pr
// Change how triggered points are rendered (maybe draw points smaller and have them get bigger, or just reduce the inner black part and always draw the outer edge
// Fix bug when turning a trigger on, all points trigger right away
// Fix points synth to use only named controls
HarmonySequencer {
    const maxTriggerCount = 16;
    const maxPointCount = 32; // 64 make trigger SynthDef too heavy to load
    const uiUpdateOscPath = "/pointPosUpdate";
    const updatePointsTriggeredStateOscPath = "/triggerCrossing";
    const c_debounceTime = 0.1;

    classvar quantizedValues;
    classvar quantizedLabels;
    classvar scales;
    classvar scaleLabels;
    classvar rootLabels;
    classvar presets;

    var win;
    var i_userView;
    var i_triggerCheckboxContainer = nil;
    var server;
    var pointsPos;
    var pointsTriggerState;
    var oscFuncs;
    var i_pointsSynth;
    var i_pointsBus;
    var i_currentTriggerSynths = #[];
    var i_synthsToClear = #[];
    var i_synthClearRoutine;
    var i_nextDebounceAction = nil;

    var i_triggerCount = 0;
    var i_triggers = #[];
    var i_bpm = 0;
    var i_scaleIdx = 0;
    var i_root = 0;
    var i_octave = 0;
    var i_globalOffset = 0;
    var i_quantizedOffset = 0;
    var i_fineOffset = 0;
    var i_speedOffset = 0;
    var i_activePointCount = 0;
    var i_probability = 0;

    *new { |server|
        // Initialize class vars
        quantizedValues = [0, 1/16, 1/8, 1/4, 1/2, 1.5/16, 1.5/8, 1.5/4, 1.5/2];
        quantizedLabels = ["0", "1/16", "1/8", "1/4", "1/2", "1/16d", "1/8d", "1/4d", "1/2d"];
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
        this.initializeValues();

        "Done initializing".postln;
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
        var synthName = ("trigger"++i_activePointCount).asSymbol;
        var notes = this.prGetNotes();

        if (i_activePointCount < 1) {
            ^nil;
        };

        // TODO use SynthDef.wrap to encapsulate duplicate code in SynthDefs below
        if (i_activePointCount == 1)
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
                var notes = \notes.kr(Array.series(i_activePointCount, 48, 1));
                var points = i_pointsBus.kr(i_activePointCount);
                points.do { |point, i|
                    var trigger = Changed.kr(PulseCount.kr(point - \offset.kr(0)));
                    var prob = TRand.kr(trig: trigger) <= \probability.kr(1);
                    SendReply.kr(trigger*prob, updatePointsTriggeredStateOscPath, [i, notes[i]]);
                };
            });
        };
        synth = synthDef.play(server, [probability: i_probability, notes: notes, offset: offset], addAction: \addToTail);
        NodeWatcher.register(synth);

        i_currentTriggerSynths = i_currentTriggerSynths.add(synth);
    }

    /** Recreate all trigger synths */
    prRefreshTriggerSynths {
        // Update synths to be cleared
        i_synthsToClear = i_synthsToClear.addAll(i_currentTriggerSynths);
        i_currentTriggerSynths = Array.new(maxTriggerCount);

        // Create new synth
        i_triggers.do {|trig, i|
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

    triggerCount { ^i_triggerCount }
    triggerCount_ { |value|
        i_triggerCount = value.clip(1, maxTriggerCount).asInteger;
        this.triggers_(this.triggers()); // update triggers array
        defer { this.prCreateTriggerControls() }
    }

    triggers { ^i_triggers }
    triggers_ { |value|
        var padded = value.clipExtend(value.size.min(i_triggerCount)); // Limit length
        i_triggers = padded ++ Array.fill(i_triggerCount - padded.size, false); // Pad with false
        this.prRefreshTriggerSynths();
    }

    bpm { ^i_bpm }
    bpm_ { |value|
        i_bpm = value;
        server.bind { i_pointsSynth.set(\bpm, i_bpm) };
    }

    scaleIndex { ^i_scaleIdx }
    scaleIndex_ {|value|
        i_scaleIdx = value;
        server.bind {
            i_currentTriggerSynths.do {|synth| synth.set(\notes, this.prGetNotes()) };
        };
    }

    root { ^i_root }
    root_ {|value|
        i_root = value;
        server.bind {
            i_currentTriggerSynths.do {|synth| synth.set(\notes, this.prGetNotes()) };
        };
    }

    octave { ^i_octave }
    octave_ {|value|
        i_octave = value;
        server.bind {
            i_currentTriggerSynths.do {|synth| synth.set(\notes, this.prGetNotes()) };
        };
    }

    globalOffset { ^i_globalOffset }
    globalOffset_ {|value|
        i_globalOffset = value;
        server.bind{ i_pointsSynth.set(\globalOffset, this.globalOffset()) };
    }

    quantizedOffset { ^i_quantizedOffset }
    quantizedOffset_ {|value|
        i_quantizedOffset = value;
        server.bind{ i_pointsSynth.set(\quantizedOffsets, Array.series(maxPointCount, step: quantizedValues[this.quantizedOffset()])) };
    }

    fineOffset { ^i_fineOffset }
    fineOffset_ {|value|
        i_fineOffset = value;
        server.bind{ i_pointsSynth.set(\fineOffsets, Array.series(maxPointCount, step: this.fineOffset() * 0.1)) };
    }

    speedOffset { ^i_speedOffset }
    speedOffset_ {|value|
        i_speedOffset = value;
        server.bind{ i_pointsSynth.set(\speedOffsets, Array.series(maxPointCount, step: this.speedOffset() * 0.1)) };
    }

    activePointCount { ^i_activePointCount }
    activePointCount_ {|value|
        // Breaks when set to 1 or when scrolled too fast
        i_activePointCount = value.clip(1, maxPointCount).asInteger;
        this.prRefreshTriggerSynths();
    }

    probability { ^i_probability }
    probability_ {|value|
        i_probability = value;
        server.bind {
            i_currentTriggerSynths.do {|synth| synth.set(\probability, this.probability()) };
        };
    }

    prGetNotes{
        ^i_activePointCount.collect({|i| i_root + (12 * i_octave) + scales[i_scaleIdx].performDegreeToKey(i)})
    }

    gui {
        |refreshRate = 30|
        var refreshOscFunc;
        var debounceRoutine;

        server.bind { i_pointsSynth.set(\refreshRate, refreshRate) };

        if (win.notNil) {
            win.front;
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
            win = Window.new("Harmony sequencer", Rect((screen.width - width)/2, (screen.height + height)/2, width, height)).onClose_({ refreshOscFunc.free; debounceRoutine.free; });

            i_userView = UserView().background_(Color.white).drawFunc_({|view|
                var width = view.bounds.width;
                var height = view.bounds.height;
                var step = width / this.triggerCount();
                var pointHeightStep = height / (this.activePointCount()+1);

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
                    var xPos = pointsPos[i];
                    var yPos = (i+1) * pointHeightStep;
                    Pen.fillColor_(Color.gray(1.0-pointsTriggerState[i]));
                    Pen.addArc(((xPos+(this.triggerCount*2).reciprocal % 1.0) * width)@yPos, 3, 0, 2pi); // Shift xpos to match drawn line offset
                    Pen.perform([\stroke, \fill][pointsTriggerState[i].ceil.asInteger]);
                };

                // Reset all points trigger crossing state
                maxPointCount.do {|i| pointsTriggerState[i] = (pointsTriggerState[i] - 0.1).max(0.0);} // Fade over 10 frames
            });

            this.prCreateTriggerControls();

            win.layout_(VLayout(
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

            win.front;
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
