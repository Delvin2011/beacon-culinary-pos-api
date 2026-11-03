package com.beaconculinary.api.menu;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@AllArgsConstructor
public class ComponentCatalogService {
    private final ComponentCatalogRepository componentCatalogRepository;
    private final ComponentCategoryRepository componentCategoryRepository;
    private final MealMapper mealMapper;

    /** Filters combine (AND); with none, every component (active and inactive), by name. */
    public List<ComponentCatalogDto> search(Long categoryId, boolean uncategorised, String name) {
        var nameFilter = name == null || name.isBlank() ? null : name.trim();
        return componentCatalogRepository.search(categoryId, uncategorised, nameFilter).stream()
                .map(mealMapper::toDto).toList();
    }

    public ComponentCatalogDto create(CreateComponentCatalogRequest request) {
        var entity = new ComponentCatalog();
        entity.setName(request.getName());
        entity.setExtraPrice(request.getExtraPrice());
        if (request.getCategoryId() != null) {
            entity.setCategory(activeCategory(request.getCategoryId()));
        }
        componentCatalogRepository.save(entity);
        return mealMapper.toDto(entity);
    }

    public ComponentCatalogDto update(Long id, UpdateComponentCatalogRequest request) {
        var entity = componentCatalogRepository.findById(id).orElseThrow(ComponentCatalogNotFoundException::new);
        entity.setName(request.getName());
        entity.setExtraPrice(request.getExtraPrice());
        entity.setActive(request.isActive());
        if (request.getCategoryId() != null) {
            entity.setCategory(activeCategory(request.getCategoryId()));
        }
        componentCatalogRepository.save(entity);
        return mealMapper.toDto(entity);
    }

    private ComponentCategory activeCategory(Long categoryId) {
        return componentCategoryRepository.findById(categoryId)
                .filter(ComponentCategory::isActive)
                .orElseThrow(() -> MenuRuleException.badRequest("CATEGORY_INVALID",
                        "Component category " + categoryId + " doesn't exist or is inactive."));
    }
}
