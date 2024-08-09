Parameter {
    var i_value;
    var i_get;
    var i_set;

    *new { |initialValue = 0, get = nil, set = nil|
        ^super.new.init(initialValue, get, set);
    }

    init { |initialValue, get, set|
        i_value = initialValue;
        i_get = get ? { |val| val };
        i_set = set ? { |val| val };
    }

    value { ^i_get.(i_value) }
    value_ { |val| i_value = i_set.(val) }

    get { ^this.value(); }
    set{ |val| this.value_(val) }
}

ObservableParameter : Parameter {
    var i_observer;

    *new { |initialValue = 0, get = nil, set = nil|
        ^super.new.init(initialValue, get, set);
    }

    init { |initialValue, get, set|
        super.init(initialValue, get, set);
        i_observer = Observer.new;
    }

    registeredKeys { ^i_observer.keys }
    register { |key, action| i_observer.register(key, action) }
    unregister { |key| i_observer.unregister(key) }

    value_ { |val| super.value_(val); i_observer.notify(this.value()); }
}

DebouncedObservableParameter : ObservableParameter {
    var i_debouncer;

    *new { |initialValue = 0, get = nil, set = nil, rate = 0.1|
        ^super.new.init(initialValue, get, set, rate);
    }

    init { |initialValue, get, set, rate|
        super.init(initialValue, get, set);
        i_debouncer = Debouncer({|val| super.value_(val) }, rate);
    }

    value_ { |val| i_debouncer.update(val); }
}