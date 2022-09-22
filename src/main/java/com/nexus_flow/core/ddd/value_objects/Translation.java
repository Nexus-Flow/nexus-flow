package com.nexus_flow.core.ddd.value_objects;

import java.io.Serializable;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

public final class Translation {

    protected LanguageCode languageCode;
    protected String       value;

    private Translation() {
    }

    public Translation(LanguageCode languageCode, TranslationValue value) {
        this.languageCode = languageCode;
        this.value        = value.getValue() != null ? value.getValue() : "";
    }

    public Translation(LanguageCode languageCode) {
        this.languageCode = languageCode;
        this.value        = "";
    }

    public static List<Translation> listBuilder(Map<String, String> translationsMap) {
        return translationsMap.entrySet().stream()
                .map(entry -> new Translation(new LanguageCode(entry.getKey()), new TranslationValue(entry.getValue())))
                .collect(Collectors.toList());
    }

    public static List<Translation> listBuilderSerializable(Map<String, Serializable> translationsMap) {
        return listBuilder(translationsMap.entrySet().stream().collect(Collectors.toMap(
                Map.Entry::getKey, entry -> (String) entry.getValue()
                           ))
        );
    }

    public LanguageCode getLanguageCode() {
        return languageCode;
    }

    public String getValue() {
        return value;
    }

    public void clean() {
        this.value = "";
    }

    @Override
    public int hashCode() {
        return Objects.hash(languageCode, value);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Translation that = (Translation) o;
        return Objects.equals(languageCode, that.languageCode) &&
               Objects.equals(value, that.value);
    }
}


