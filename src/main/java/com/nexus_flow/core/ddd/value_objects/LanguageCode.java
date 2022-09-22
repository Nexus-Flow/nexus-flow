package com.nexus_flow.core.ddd.value_objects;

import com.nexus_flow.core.ddd.exceptions.WrongFormat;

import java.util.Objects;

public final class LanguageCode {

    protected String value;

    private LanguageCode() {
    }
    
    public LanguageCode(String language) {
        checkLanguage(language);
        this.value = language.toUpperCase();
    }

    public String getValue() {
        return value;
    }

    private void checkLanguage(String language) {
        if (language.length() != 2) {
            throw new WrongFormat("Language (" + language + ") must be two letters length.");
        }
    }


    @Override
    public int hashCode() {
        return Objects.hash(value);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof LanguageCode)) {
            return false;
        }
        LanguageCode that = (LanguageCode) o;
        return Objects.equals(value, that.value);
    }

    @Override
    public String toString() {
        return this.value;
    }
}
