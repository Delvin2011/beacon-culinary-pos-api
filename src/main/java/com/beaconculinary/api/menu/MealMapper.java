package com.beaconculinary.api.menu;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

@Mapper(componentModel = "spring")
public interface MealMapper {
    MealPeriodDto toDto(MealPeriod mealPeriod);

    ComponentCatalogDto toDto(ComponentCatalog componentCatalog);

    @Mapping(target = "components", source = "components")
    MealCatalogDto toDto(MealCatalog mealCatalog);

    default List<ComponentCatalogDto> mapComponents(List<MealCatalogComponent> links) {
        return links.stream().map(link -> toDto(link.getComponentCatalog())).toList();
    }

    @Mapping(target = "mealPeriodId", source = "mealPeriod.id")
    DailyMealOptionDto toDto(DailyMealOption dailyMealOption);

    @Mapping(target = "componentCatalogId", source = "componentCatalog.id")
    @Mapping(target = "componentName", source = "componentCatalog.name")
    @Mapping(target = "mealPeriodId", source = "mealPeriod.id")
    DailyComponentStockDto toDto(DailyComponentStock dailyComponentStock);
}
