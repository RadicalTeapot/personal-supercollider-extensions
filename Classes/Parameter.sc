Parameter {
    var i_value;
    var i_get;
    var i_set;

    *new { |initialValue = 0, get = nil, set = nil|
        ^super.new.init(initialValue, get, set);
    }

    init { |initialValue, getFunc, setFunc|
        i_value = initialValue;
        i_get = getFunc ? { |val| val };
        i_set = setFunc ? { |val| val };
    }

    value { ^i_get.(i_value) }
    value_ { |val| i_value = i_set.(val) }
}