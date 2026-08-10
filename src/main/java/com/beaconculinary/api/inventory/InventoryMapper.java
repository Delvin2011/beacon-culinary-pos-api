package com.beaconculinary.api.inventory;

import org.mapstruct.Mapping;
import org.mapstruct.Mapper;

import java.util.List;

@Mapper(componentModel = "spring")
public interface InventoryMapper {
    IngredientDto toDto(Ingredient ingredient);

    @Mapping(target = "componentCatalogId", source = "componentCatalog.id")
    RecipeDto toDto(Recipe recipe);

    @Mapping(target = "ingredientId", source = "ingredient.id")
    @Mapping(target = "ingredientName", source = "ingredient.name")
    @Mapping(target = "unit", source = "ingredient.unit")
    RecipeLineDto toDto(RecipeLine recipeLine);

    @Mapping(target = "ingredientId", source = "ingredient.id")
    @Mapping(target = "ingredientName", source = "ingredient.name")
    @Mapping(target = "receivedById", source = "receivedBy.id")
    GrvDto toDto(Grv grv);

    @Mapping(target = "ingredientId", source = "ingredient.id")
    @Mapping(target = "ingredientName", source = "ingredient.name")
    WasteEntryDto toDto(WasteEntry wasteEntry);

    PurchaseOrderDto toDto(PurchaseOrder purchaseOrder);

    List<PurchaseOrderDto> toPurchaseOrderDtoList(List<PurchaseOrder> purchaseOrders);

    @Mapping(target = "ingredientId", source = "ingredient.id")
    @Mapping(target = "ingredientName", source = "ingredient.name")
    PurchaseOrderLineDto toDto(PurchaseOrderLine purchaseOrderLine);
}
