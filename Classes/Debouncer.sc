Debouncer {
    const c_minRate = 0.001;
    var <>rate;
    var <>updateCallback;
    var <>action;
    var i_nextUpdate;
    var i_updateRoutine;

    *new { |action = nil, rate = 0.1, updateCallback = nil|
        ^super.new.init(action, rate, updateCallback);
    }

    init { |actionValue, rateValue, updateCallbackValue|
        action = actionValue;
        rate = (rateValue ? c_minRate).max(c_minRate);
        updateCallback = updateCallbackValue;

        i_nextUpdate = nil;
        i_updateRoutine = Routine {
            while ({i_nextUpdate.notNil}) {
                // Note: it's safe to call nil as a function so no need to check for nil here
                var result = i_nextUpdate.value;
                i_nextUpdate = nil;
                updateCallback.value(result);
                rate.yield;
            };
        };
    }

    update { |...args|
        // Note: it's safe to call nil as a function so no need to check for nil here
        i_nextUpdate = { var actionArgs = args; action.valueArray(actionArgs); };
        if (i_updateRoutine.isPlaying.not) { i_updateRoutine.reset.play; };
    }

    free {
        i_nextUpdate = nil;
        i_updateRoutine.stop;
    }
}