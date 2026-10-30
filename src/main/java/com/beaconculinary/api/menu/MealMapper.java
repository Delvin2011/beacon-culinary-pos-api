package com.beaconculinary.api.menu;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

@Mapper(componentModel = "spring")
public interface MealMapper {
    MealPeriodDto toDto(MealPeriod mealPeriod);

    ComponentCatalogDto toDto(ComponentCatalog componentCatalog);

    // outToday depends on today's date, so MealCatalogService sets it.
    @Mapping(target = "components", source = "components")
    @Mapping(target = "category", expression = "java(CatalogCategoryRefDto.of(mealCatalog.getCategory()))")
    @Mapping(target = "outToday", ignore = true)
    @Mapping(target = "stockStatus", ignore = true)
    @Mapping(target = "unitsAvailable", ignore = true)
    MealCatalogDto toDto(MealCatalog mealCatalog);

    default List<ComponentCatalogDto> mapComponents(List<MealCatalogComponent> links) {
        return links.stream().map(link -> toDto(link.getComponentCatalog())).toList();
    }

    @Mapping(target = "mealCatalogId", source = "mealCatalog.id")
    @Mapping(target = "mealPeriodId", source = "mealPeriod.id")
    @Mapping(target = "period", expression = "java(MealPeriodRefDto.of(dailyMealOption.getMealPeriod()))")
    @Mapping(target = "group", source = "mealCatalog.category.group")
    @Mapping(target = "category", expression = "java(CatalogCategoryRefDto.of(dailyMealOption.getMealCatalog().getCategory()))")
    @Mapping(target = "outToday", expression = "java(dailyMealOption.getMealCatalog().isOutOn(dailyMealOption.getOptionDate()))")
    @Mapping(target = "stockStatus", ignore = true)
    @Mapping(target = "unitsAvailable", ignore = true)
    DailyMealOptionDto toDto(DailyMealOption dailyMealOption);

    @Mapping(target = "componentCatalogId", source = "componentCatalog.id")
    @Mapping(target = "componentName", source = "componentCatalog.name")
    @Mapping(target = "mealPeriodId", source = "mealPeriod.id")
    DailyComponentStockDto toDto(DailyComponentStock dailyComponentStock);

    DailyPlanOptionDto toPlanDto(DailyMealOption dailyMealOption);

    @Mapping(target = "componentName", source = "componentCatalog.name")
    DailyPlanComponentStockDto toPlanDto(DailyComponentStock dailyComponentStock);
}
