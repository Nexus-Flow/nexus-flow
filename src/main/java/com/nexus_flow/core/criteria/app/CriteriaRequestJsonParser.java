package com.nexus_flow.core.criteria.app;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.nexus_flow.core.ddd.exceptions.WrongFormat;
import org.springframework.boot.jackson.JsonComponent;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

@JsonComponent
public class CriteriaRequestJsonParser {

    private CriteriaRequestJsonParser() {
    }

    public static class CriteriaRequestJsonSerializer extends JsonSerializer<CriteriaRequest<? extends CriterionRequest>> {

        @Override
        public void serialize(CriteriaRequest<? extends CriterionRequest> criteriaRequest,
                              JsonGenerator jsonGenerator,
                              SerializerProvider serializerProvider) throws IOException {
            parseCriteria(criteriaRequest, jsonGenerator);

        }

        private void parseCriteria(CriteriaRequest<? extends CriterionRequest> criteriaRequest,
                                   JsonGenerator jsonGenerator) throws IOException {
            if (criteriaRequest instanceof AndCriteriaRequest) {
                parseAndCriteria((AndCriteriaRequest<? extends CriterionRequest>) criteriaRequest, jsonGenerator);
            } else {
                parseOrCriteria((OrCriteriaRequest<? extends CriterionRequest>) criteriaRequest, jsonGenerator);
            }
        }

        private void parseAndCriteria(AndCriteriaRequest<? extends CriterionRequest> andCriteriaRequest,
                                      JsonGenerator jsonGenerator) throws IOException {

            jsonGenerator.writeStartObject();
            jsonGenerator.writeArrayFieldStart("and");
            generateFiltersArray(andCriteriaRequest, jsonGenerator);
            jsonGenerator.writeEndArray();
            jsonGenerator.writeEndObject();


        }

        private void generateFiltersArray(CriteriaRequest<? extends CriterionRequest> andCriteriaRequest,
                                          JsonGenerator jsonGenerator) throws IOException {
            for (CriterionRequest criteria : andCriteriaRequest.getFilters()) {
                if (criteria instanceof FilterRequest) {
                    jsonGenerator.writeObject(criteria);
                } else {
                    parseCriteria((CriteriaRequest<? extends CriterionRequest>) criteria, jsonGenerator);
                }
            }
        }

        private void parseOrCriteria(OrCriteriaRequest<? extends CriterionRequest> orCriteriaRequest,
                                     JsonGenerator jsonGenerator) throws IOException {
            jsonGenerator.writeStartObject();
            jsonGenerator.writeArrayFieldStart("or");
            generateFiltersArray(orCriteriaRequest, jsonGenerator);
            jsonGenerator.writeEndArray();
            jsonGenerator.writeEndObject();

        }

    }

    public static class CriteriaRequestJsonDeserializer extends JsonDeserializer<CriteriaRequest<? extends CriterionRequest>> {

        @Override
        public CriteriaRequest<? extends CriterionRequest> deserialize(JsonParser jsonParser,
                                                                       DeserializationContext deserializationContext) throws IOException {

            JsonNode treeNode = jsonParser.getCodec().readTree(jsonParser);
            JsonNode filters  = treeNode.get("filters");
            if (filters.isNull()) return new AndCriteriaRequest<>();
            return parseNode(filters);
        }

        private CriteriaRequest<? extends CriterionRequest> parseNode(JsonNode node) {
            if (node.fieldNames().hasNext()) {
                String fieldName = node.fieldNames().next();
                if ("AND".equals(fieldName.toUpperCase(Locale.ROOT))) {
                    return parseAndNode(node.get(fieldName));
                }
                if ("OR".equals(fieldName.toUpperCase(Locale.ROOT))) {
                    return parseOrNode(node.get(fieldName));
                }
            }
            throw new WrongFormat("Only AND/OR objects can be processed");

        }

        private AndCriteriaRequest<? extends CriterionRequest> parseAndNode(JsonNode node) {
            assertIsArrayNode(node);
            AndCriteriaRequest<CriterionRequest> andCriteria  = new AndCriteriaRequest<>();
            Iterator<JsonNode>                   nodeIterator = node.elements();
            while (nodeIterator.hasNext()) {
                JsonNode next = nodeIterator.next();
                if (isFilterObject(next)) {
                    andCriteria.and(parseFilterNode(next));
                } else {
                    andCriteria.and(parseNode(next));
                }
            }
            return andCriteria;
        }

        private OrCriteriaRequest<? extends CriterionRequest> parseOrNode(JsonNode node) {
            assertIsArrayNode(node);
            OrCriteriaRequest<CriterionRequest> orCriteria = new OrCriteriaRequest<>();

            Iterator<JsonNode> nodeIterator = node.elements();
            while (nodeIterator.hasNext()) {
                JsonNode next = nodeIterator.next();
                if (isFilterObject(next)) {
                    orCriteria.or(parseFilterNode(next));
                } else {
                    orCriteria.or(parseNode(next));
                }
            }
            return orCriteria;
        }

        private CriterionRequest parseFilterNode(JsonNode node) {
            final String field = node.get("field").asText();
            final String operator = node.get("filterType") != null ?
                    node.get("filterType").asText() :
                    node.get("operator").asText();
            final String format = node.get("valueType") != null ? node.get("valueType").asText() : null;

            return new FilterRequest(field, operator, format, parseValues(node.get("value")));
        }

        private List<String> parseValues(JsonNode values) {
            if (!values.isArray()) {
                return List.of(values.asText());
            } else {
                List<String> valueList = new ArrayList<>();
                for (JsonNode n : values) {
                    valueList.add(n.asText());
                }
                return valueList;
            }
        }

        private boolean isFilterObject(JsonNode node) {
            return node.get("field") != null &&
                    (node.get("operator") != null || node.get("filterType") != null);
        }


        private void assertIsArrayNode(JsonNode node) {
            if (!node.isArray()) {
                throw new WrongFormat("AND/OR objects must contains an Array of [filter, and, or]");
            }
        }

    }
}