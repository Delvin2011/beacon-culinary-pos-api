package com.beaconculinary.api.menu;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Component categories: create, rename, re-sort and (de)activate; never delete. A category with
 * active components can't be deactivated. */
@Service
@AllArgsConstructor
public class ComponentCategoryService {
    private static final Comparator<ComponentCategory> ORDER =
            Comparator.comparingInt(ComponentCategory::getSortOrder).thenComparing(ComponentCategory::getName);

    private final ComponentCategoryRepository categoryRepository;

    @Transactional(readOnly = true)
    public List<ComponentCategoryDto> getAll(boolean includeInactive) {
        var counts = activeCounts();
        return categoryRepository.findAll().stream()
                .filter(category -> includeInactive || category.isActive())
                .sorted(ORDER)
                .map(category -> toDto(category, counts.getOrDefault(category.getId(), 0L)))
                .toList();
    }

    @Transactional
    public ComponentCategoryDto create(CreateComponentCategoryRequest request) {
        var name = request.getName().trim();
        if (categoryRepository.existsByNameIgnoreCase(name)) {
            throw MenuRuleException.conflict("CATEGORY_NAME_TAKEN", "A component category named '" + name + "' already exists.");
        }

        var category = new ComponentCategory();
        category.setName(name);
        category.setSortOrder(request.getSortOrder());
        categoryRepository.save(category);
        return toDto(category, 0);
    }

    @Transactional
    public ComponentCategoryDto update(Long id, UpdateComponentCategoryRequest request) {
        var category = categoryRepository.findById(id).orElseThrow(ComponentCategoryNotFoundException::new);
        var name = request.getName().trim();
        if (categoryRepository.existsByNameIgnoreCaseAndIdNot(name, id)) {
            throw MenuRuleException.conflict("CATEGORY_NAME_TAKEN", "A component category named '" + name + "' already exists.");
        }
        var activeComponents = activeCounts().getOrDefault(id, 0L);
        if (category.isActive() && !request.isActive() && activeComponents > 0) {
            throw MenuRuleException.conflict("CATEGORY_HAS_ITEMS", "'" + category.getName() + "' has " + activeComponents
                    + " active component" + (activeComponents == 1 ? "" : "s") + ". Move or deactivate them first.");
        }

        category.setName(name);
        category.setSortOrder(request.getSortOrder());
        category.setActive(request.isActive());
        categoryRepository.save(category);
        return toDto(category, activeComponents);
    }

    private Map<Long, Long> activeCounts() {
        return categoryRepository.countActiveComponentsByCategory().stream()
                .collect(Collectors.toMap(ComponentCategoryRepository.CategoryCount::getCategoryId,
                        ComponentCategoryRepository.CategoryCount::getCount));
    }

    private static ComponentCategoryDto toDto(ComponentCategory category, long componentCount) {
        return new ComponentCategoryDto(category.getId(), category.getName(), category.getSortOrder(),
                category.isActive(), componentCount);
    }
}
