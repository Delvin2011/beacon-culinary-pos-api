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

    @Mapping(target = "purchaseOrderId", source = "purchaseOrder.id")
    @Mapping(target = "receivedById", source = "receivedBy.id")
    GrvDto toDto(Grv grv);

    List<GrvDto> toGrvDtoList(List<Grv> grvs);

    @Mapping(target = "ingredientId", source = "ingredient.id")
    @Mapping(target = "ingredientName", source = "ingredient.name")
    @Mapping(target = "purchaseOrderLineId", source = "purchaseOrderLine.id")
    @Mapping(target = "receiptVariance",
            expression = "java(line.getQuantityOrdered() == null ? null : " +
                    "line.getQuantityReceived().subtract(line.getQuantityOrdered()))")
    GrvLineDto toDto(GrvLine line);

    @Mapping(target = "ingredientId", source = "ingredient.id")
    @Mapping(target = "ingredientName", source = "ingredient.name")
    @Mapping(target = "locationId", source = "location.id")
    @Mapping(target = "locationName", source = "location.name")
    WasteEntryDto toDto(WasteEntry wasteEntry);

    @Mapping(target = "stockRequestId", source = "stockRequest.id")
    PurchaseOrderDto toDto(PurchaseOrder purchaseOrder);

    List<PurchaseOrderDto> toPurchaseOrderDtoList(List<PurchaseOrder> purchaseOrders);

    @Mapping(target = "ingredientId", source = "ingredient.id")
    @Mapping(target = "ingredientName", source = "ingredient.name")
    PurchaseOrderLineDto toDto(PurchaseOrderLine purchaseOrderLine);

    LocationDto toDto(Location location);

    @Mapping(target = "requestedById", source = "requestedBy.id")
    @Mapping(target = "requestedByName", source = "requestedBy.name")
    @Mapping(target = "actionedById", source = "actionedBy.id")
    @Mapping(target = "dailyPlanPeriodId", source = "dailyPlanPeriod.id")
    @Mapping(target = "locationId", source = "location.id")
    @Mapping(target = "locationName", source = "location.name")
    StockRequestDto toDto(StockRequest stockRequest);

    List<StockRequestDto> toStockRequestDtoList(List<StockRequest> stockRequests);

    @Mapping(target = "ingredientId", source = "ingredient.id")
    @Mapping(target = "ingredientName", source = "ingredient.name")
    StockRequestLineDto toDto(StockRequestLine line);

    @Mapping(target = "locationId", source = "location.id")
    @Mapping(target = "locationName", source = "location.name")
    @Mapping(target = "submittedById", source = "submittedBy.id")
    @Mapping(target = "submittedByName", source = "submittedBy.name")
    @Mapping(target = "reviewedById", source = "reviewedBy.id")
    StockTakeDto toDto(StockTake stockTake);

    List<StockTakeDto> toStockTakeDtoList(List<StockTake> stockTakes);

    @Mapping(target = "ingredientId", source = "ingredient.id")
    @Mapping(target = "ingredientName", source = "ingredient.name")
    StockTakeLineDto toDto(StockTakeLine line);
}
