Observer {
    var i_actions;
    var i_orderedKeys;

    *new { |model|
        ^super.new.init(model);
    }

    init { |model|
        i_actions = IdentityDictionary.new;
        i_orderedKeys = List.new;
    }

    keys { ^i_actions.keys }

    register { |key, action|
        i_actions.put(key, action);
        i_orderedKeys.add(key);
    }

    unregister { |key|
        // Grab the index rather than checking in dictionary as it will be used later to update the list
        var index = i_orderedKeys.indexOf(key);
        if (index.isNil) {
            ("Key"+key+"not registered for notification").warn;
            ^nil;
        };

        i_actions.removeAt(key);
        i_orderedKeys.removeAt(index);
    }

    notify { |...args|
        i_orderedKeys.do { |key| i_actions[key].valueArray(args) };
    }
}
