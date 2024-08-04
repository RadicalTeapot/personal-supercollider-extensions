ObserverWrapper {
    var <key;
    var i_notificationCallback;

    *new { |key, notificationCallback|
        ^super.new.init(key, notificationCallback);
    }

    init { |itemKey, notificationCallback|
        key = itemKey;
        i_notificationCallback = notificationCallback;
    }

    value { |value|
        i_notificationCallback.(value);
    }
}

ObserverWrapperFactory {
    *guiObserver { |key, guiItem|
        ^ObserverWrapper(key, {|value| defer { guiItem.value_(value) } });
    }
}

ObserverManager {
    var i_observers;

    *new {
        ^super.new.init;
    }

    init {
        i_observers = IdentityDictionary.new;
    }

    prIsValidObserver { |observer|
        ^(observer.respondsTo('key') && observer.respondsTo('value'));
    }

    addObserver { |key, observer|
        var observerArray;

        if (this.prIsValidObserver(observer).not) {
            "Observer is not valid".error;
            ^nil;
        };

        observerArray = i_observers.atFail(key, {Array.new});
        i_observers.put(key, observerArray.add(observer));
    }

    removeObserver { |key, observer|
        if (this.prIsValidObserver(observer).not) {
            "Observer is not valid".error;
            ^nil;
        };

        if (i_observers.includesKey(key).not) {
            ("Key"+key+"not found in observer dictionary").error;
            ^nil;
        };

        i_observers[key] = i_observers[key].reject { |item| item.key === observer.key };
    }

    notifyObservers { |key, value|
        if (i_observers.includesKey(key).not) {
            ("Key"+key+"not found in observer dictionary").warn;
            ^nil;
        };

        i_observers[key].do { |observer| observer.value(value) };
    }
}