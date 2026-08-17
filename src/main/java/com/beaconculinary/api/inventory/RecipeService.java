package com.beaconculinary.api.inventory;

import com.beaconculinary.api.menu.ComponentCatalogRepository;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.stream.Collectors;

@Service
@AllArgsConstructor
public class RecipeService {
    private final RecipeRepository recipeRepository;
    private final ComponentCatalogRepository componentCatalogRepository;
    private final IngredientRepository ingredientRepository;
    private final InventoryMapper inventoryMapper;

    @Transactional(readOnly = true)
    public RecipeDto get(Long componentCatalogId) {
        var recipe = recipeRepository.findWithLinesByComponentCatalogId(componentCatalogId)
                .orElseThrow(RecipeNotFoundException::new);
        return inventoryMapper.toDto(recipe);
    }

    // PUT replaces the recipe wholesale (delete-and-recreate its lines) — simpler than diffing,
    // and recipes are edited infrequently.
    @Transactional
    public RecipeDto update(Long componentCatalogId, UpdateRecipeRequest request) {
        var componentCatalog = componentCatalogRepository.findById(componentCatalogId)
                .orElseThrow(() -> new InvalidInventoryRequestException("componentCatalogId does not exist."));

        var recipe = recipeRepository.findWithLinesByComponentCatalogId(componentCatalogId).orElseGet(() -> {
            var created = new Recipe();
            created.setComponentCatalog(componentCatalog);
            return created;
        });
        recipe.setBatchSize(request.getBatchSize());
        recipe.getLines().clear();

        var ingredientIds = request.getLines().stream().map(RecipeLineRequest::getIngredientId).toList();
        var ingredients = ingredientRepository.findAllByIdIn(ingredientIds);
        if (ingredients.size() != new HashSet<>(ingredientIds).size()) {
            throw new InvalidInventoryRequestException("One or more ingredientIds do not exist.");
        }
        var ingredientsById = ingredients.stream().collect(Collectors.toMap(Ingredient::getId, i -> i));

        for (var lineRequest : request.getLines()) {
            var line = new RecipeLine();
            line.setRecipe(recipe);
            line.setIngredient(ingredientsById.get(lineRequest.getIngredientId()));
            line.setQuantity(lineRequest.getQuantity());
            recipe.getLines().add(line);
        }

        recipeRepository.save(recipe);
        return inventoryMapper.toDto(recipe);
    }
}
