package com.nexus_flow.core.criteria.infrastructure.jpa;


import com.nexus_flow.core.criteria.domain.AndCriteria;
import com.nexus_flow.core.criteria.domain.Criterion;
import com.nexus_flow.core.criteria.domain.OrCriteria;
import com.nexus_flow.core.criteria.domain.PagedCriteria;
import com.nexus_flow.core.criteria.domain.filter.Filter;
import com.nexus_flow.core.criteria.domain.filter.FilterOperator;
import com.nexus_flow.core.criteria.infrastructure.FilterConverter;
import com.nexus_flow.core.ddd.exceptions.WrongFormat;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.jpa.domain.Specification;

import java.lang.reflect.Field;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class JpaSpecificationCriteriaConverter<T> {

    private final Map<FilterOperator, Function<Filter, Specification<T>>> specificationsMap;
    private final Map<String, Class<?>>                                   entityFields;
    private final List<String>                                            jsonColumns;
    private final FilterConverter filterConverter;

    public JpaSpecificationCriteriaConverter(Class<T> entityClass,
                                             FilterConverter filterConverter) {
        this.filterConverter   = filterConverter;
        this.specificationsMap = fillSpecificationsMap();
        this.entityFields      = collectEntityFields(entityClass);
        this.jsonColumns       = collectEntityColumnInfo(entityClass);
    }

    private Map<String, Class<?>> collectEntityFields(Class<T> entityClass) {
        return Stream.of(entityClass.getDeclaredFields())
                .collect(Collectors.toMap(Field::getName, Field::getType));
    }

    private List<String> collectEntityColumnInfo(Class<T> entityClass) {
        return Stream.of(entityClass.getDeclaredFields())
                .filter(field -> field.isAnnotationPresent(JdbcTypeCode.class))
                .filter(field -> {
                    Integer columnType = field.getAnnotation(JdbcTypeCode.class).value();
                    return columnType.equals(SqlTypes.JSON);
                })
                .map(Field::getName)
                .collect(Collectors.toList());
    }

    private Map<FilterOperator, Function<Filter, Specification<T>>> fillSpecificationsMap() {
        EnumMap<FilterOperator, Function<Filter, Specification<T>>> map = new EnumMap<>(FilterOperator.class);
        map.put(FilterOperator.EQUAL, JpaSpecificationCriteriaConverter.this::equalsSpecificationTransformer);
        map.put(FilterOperator.NOT_EQUAL, JpaSpecificationCriteriaConverter.this::notEqualsSpecificationTransformer);
        map.put(FilterOperator.GT, JpaSpecificationCriteriaConverter.this::greaterThanSpecificationTransformer);
        map.put(FilterOperator.GT_OR_EQUALS,
                JpaSpecificationCriteriaConverter.this::greaterThanOrEqualToSpecificationTransformer);
        map.put(FilterOperator.LT, JpaSpecificationCriteriaConverter.this::lessThanSpecificationTransformer);
        map.put(FilterOperator.LT_OR_EQUALS,
                JpaSpecificationCriteriaConverter.this::lessThanOrEqualToSpecificationTransformer);
        map.put(FilterOperator.CONTAINS, JpaSpecificationCriteriaConverter.this::containsSpecificationTransformer);
        map.put(FilterOperator.LIKE, JpaSpecificationCriteriaConverter.this::likeNoCaseSpecificationTransformer);
        map.put(FilterOperator.NOT_CONTAINS,
                JpaSpecificationCriteriaConverter.this::notContainsSpecificationTransformer);
        map.put(FilterOperator.IS_NULL, JpaSpecificationCriteriaConverter.this::isNullSpecificationTransformer);
        map.put(FilterOperator.IS_NOT_NULL, JpaSpecificationCriteriaConverter.this::isNotNullSpecificationTransformer);
        map.put(FilterOperator.IN, JpaSpecificationCriteriaConverter.this::inSpecificationTransformer);
        map.put(FilterOperator.NOT_IN, JpaSpecificationCriteriaConverter.this::notInSpecificationTransformer);
        map.put(FilterOperator.BETWEEN, JpaSpecificationCriteriaConverter.this::betweenSpecificationTransformer);

        return map;
    }

    /**
     * Method that will call to AndCriteria or OrCriteria converter
     *
     * @param pagedCriteria Domain criteria page to parse
     * @return Specification to pass to JPA repository method
     */
    public Specification<T> convert(PagedCriteria pagedCriteria) {
        return convert(pagedCriteria.getCriteria());
    }

    public Specification<T> convert(Criterion criterion) {
        if (criterion instanceof AndCriteria) {
            return convert((AndCriteria<? extends Criterion>) criterion);
        } else if (criterion instanceof OrCriteria) {
            return convert((OrCriteria<? extends Criterion>) criterion);
        } else if (criterion instanceof Filter) {
            return convert((Filter) criterion);
        } else {
            throw new IllegalArgumentException("Criteria type not exist.");
        }

    }


    /**
     * Watch out when searching in a jsonb field: by now we can only check if the string provided exists as a value (not a key)
     * within the whole json, not by aggregate field.
     * That means that we can check if the string exists exactly (equals) within a value or is contained (like) in a value;
     * and also the negations (notEquals, notContains)
     *
     * @param andCriteria Domain criteria to parse
     * @return Specification to pass to JPA repository method
     */
    private Specification<T> convert(AndCriteria<? extends Criterion> andCriteria) {
        List<? extends Criterion> criteria = andCriteria.getFilters();
        return criteria.stream()
                .map(this::convert)
                .reduce(Specification::and).orElse(Specification.where(null));
    }

    /**
     * Watch out when searching in a jsonb field: by now we can only check if the string provided exists as a value (not a key)
     * within the whole json, not by aggregate field.
     * That means that we can check if the string exists exactly (equals) within a value or is contained (like) in a value;
     * and also the negations (notEquals, notContains)
     *
     * @param orCriteria Domain criteria to parse
     * @return Specification to pass to JPA repository method
     */
    private Specification<T> convert(OrCriteria<? extends Criterion> orCriteria) {
        List<? extends Criterion> criteria = orCriteria.getFilters();
        return criteria.stream()
                .map(this::convert)
                .reduce(Specification::or).orElse(Specification.where(null));
    }

    /**
     * @param filter Domain filter to convert to Specification
     * @return Specification to pass to JPA repository method
     */
    private Specification<T> convert(Filter filter) {
        checkFieldsExist(filter);
        checkJsonbFields(filter);
        return formatPredicate(filter);
    }


    private Specification<T> formatPredicate(Filter filter) {
        Function<Filter, Specification<T>> transformer = specificationsMap.get(filter.getOperator());
        return transformer.apply(filterConverter.convert(filter));
    }

    private Specification<T> equalsSpecificationTransformer(Filter filter) {
        return (root, query, cb) -> cb.equal(root.get(filter.getField().getValue()), filter.getValue().getFirst());
    }

    private Specification<T> notEqualsSpecificationTransformer(Filter filter) {
        return (root, query, cb) -> cb.notEqual(root.get(filter.getField().getValue()), filter.getValue().getFirst());
    }

    private Specification<T> greaterThanSpecificationTransformer(Filter filter) {
        return (root, query, cb) -> cb.greaterThan(root.get(filter.getField().getValue()),
                                                   filter.getValue().getFirst());
    }

    private Specification<T> greaterThanOrEqualToSpecificationTransformer(Filter filter) {
        return (root, query, cb) -> cb.greaterThanOrEqualTo(root.get(filter.getField().getValue()),
                                                            filter.getValue().getFirst());
    }

    private Specification<T> lessThanSpecificationTransformer(Filter filter) {
        return (root, query, cb) -> cb.lessThan(root.get(filter.getField().getValue()), filter.getValue().getFirst());
    }

    private Specification<T> lessThanOrEqualToSpecificationTransformer(Filter filter) {
        return (root, query, cb) -> cb.lessThanOrEqualTo(root.get(filter.getField().getValue()),
                                                         filter.getValue().getFirst());
    }

    private Specification<T> containsSpecificationTransformer(Filter filter) {
        return (root, query, cb) -> cb.like(root.get(filter.getField().getValue()),
                                            "%" + filter.getValue().getFirst() + "%");
    }

    private Specification<T> likeNoCaseSpecificationTransformer(Filter filter) {
        return (root, query, cb) -> cb.like(cb.lower(root.get(filter.getField().getValue())),
                                            "%" + filter.getValue().getFirst().toString().toLowerCase() + "%");
    }

    private Specification<T> notContainsSpecificationTransformer(Filter filter) {
        return (root, query, cb) -> cb.notLike(root.get(filter.getField().getValue()),
                                               "%" + filter.getValue().getFirst() + "%");
    }

    private Specification<T> isNullSpecificationTransformer(Filter filter) {
        return (root, query, cb) -> cb.isNull(root.get(filter.getField().getValue()));
    }

    private Specification<T> isNotNullSpecificationTransformer(Filter filter) {
        return (root, query, cb) -> cb.isNotNull(root.get(filter.getField().getValue()));
    }


    private Specification<T> betweenSpecificationTransformer(Filter filter) {
        return (root, query, cb) -> cb.between(root.get(filter.getField().getValue()),
                                               filter.getValue().getFirst(),
                                               filter.getValue().getSecond());
    }

    private Specification<T> notInSpecificationTransformer(Filter filter) {
        return (root, query, cb) -> cb.in(root.get(filter.getField().getValue())
                                                  .in(filter.getValue().getValues())
                                                  .not());
    }

    private Specification<T> inSpecificationTransformer(Filter filter) {
        return (root, query, cb) -> cb.in(root.get(filter.getField().getValue())
                                                  .in(filter.getValue().getValues()));
    }


    private void checkFieldsExist(Filter filter) {
        if (!entityFields.containsKey(filterConverter.convert(filter).getField().getValue())) {
            throw new WrongFormat("We cannot filter by field(s): " + filter.getField());
        }
    }


    private void checkJsonbFields(Filter filter) {
        // Checks if any filter is searching by a json column
        if (jsonColumns.contains(filterConverter.convert(filter).getField().getValue())) {
            throw new WrongFormat(
                    "Search by field(s) '" + filter.getField().getValue() + "'  is not allowed.");
        }
    }

}

