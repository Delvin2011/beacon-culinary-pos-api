package com.beaconculinary.api.menu;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The catalogue tree. The top-level groups are fixed (no add, rename or deactivate); subcategories
 * are added under "Light meals" or "Beverages" and can be renamed, re-sorted and deactivated, but
 * never deleted. A subcategory with active meals can't be deactivated.
 */
@Service
@AllArgsConstructor
public class CatalogCategoryService {
    private static final Comparator<CatalogCategory> ORDER =
            Comparator.comparingInt(CatalogCategory::getSortOrder).thenComparing(CatalogCategory::getName);

    private final CatalogCategoryRepository categoryRepository;

    @Transactional(readOnly = true)
    public List<CatalogCategoryDto> getTree() {
        var counts = categoryRepository.countActiveMealsByCategory().stream()
                .collect(Collectors.toMap(CatalogCategoryRepository.CategoryCount::getCategoryId,
                        CatalogCategoryRepository.CategoryCount::getCount));
        var all = categoryRepository.findAll();
        var childrenByParent = all.stream().filter(category -> !category.isTopLevel())
                .collect(Collectors.groupingBy(category -> category.getParent().getId()));

        return all.stream().filter(CatalogCategory::isTopLevel).sorted(ORDER)
                .map(top -> {
                    var children = childrenByParent.getOrDefault(top.getId(), List.of()).stream().sorted(ORDER)
                            .map(child -> toDto(child, counts.getOrDefault(child.getId(), 0L), List.of()))
                            .toList();
                    var total = counts.getOrDefault(top.getId(), 0L)
                            + children.stream().mapToLong(CatalogCategoryDto::itemCount).sum();
                    return toDto(top, total, children);
                })
                .toList();
    }

    @Transactional
    public CatalogCategoryDto create(CreateCatalogCategoryRequest request) {
        var parent = categoryRepository.findById(request.getParentId())
                .filter(CatalogCategory::acceptsSubcategories)
                .orElseThrow(() -> MenuRuleException.badRequest("CATEGORY_INVALID",
                        "Subcategories can only be added under Light meals or Beverages."));
        var name = request.getName().trim();
        if (categoryRepository.existsByParentIdAndNameIgnoreCase(parent.getId(), name)) {
            throw MenuRuleException.conflict("CATEGORY_NAME_TAKEN", "'" + name + "' already exists under " + parent.getName() + ".");
        }

        var category = new CatalogCategory();
        category.setParent(parent);
        category.setGroup(parent.getGroup());
        category.setName(name);
        category.setSortOrder(request.getSortOrder());
        categoryRepository.save(category);
        return toDto(category, 0, List.of());
    }

    @Transactional
    public CatalogCategoryDto update(Long id, UpdateCatalogCategoryRequest request) {
        var category = categoryRepository.findById(id).orElseThrow(CatalogCategoryNotFoundException::new);
        if (category.isTopLevel()) {
            throw MenuRuleException.badRequest("CATEGORY_FIXED", "'" + category.getName() + "' is a fixed group and can't be changed.");
        }
        var name = request.getName().trim();
        var parentId = category.getParent().getId();
        if (categoryRepository.existsByParentIdAndNameIgnoreCaseAndIdNot(parentId, name, id)) {
            throw MenuRuleException.conflict("CATEGORY_NAME_TAKEN",
                    "'" + name + "' already exists under " + category.getParent().getName() + ".");
        }
        var activeItems = activeItemCount(id);
        if (category.isActive() && !request.isActive() && activeItems > 0) {
            throw MenuRuleException.conflict("CATEGORY_HAS_ITEMS", "'" + category.getName() + "' has " + activeItems
                    + " active item" + (activeItems == 1 ? "" : "s") + ". Move or deactivate them first.");
        }

        category.setName(name);
        category.setSortOrder(request.getSortOrder());
        category.setActive(request.isActive());
        categoryRepository.save(category);
        return toDto(category, activeItems, List.of());
    }

    private long activeItemCount(Long categoryId) {
        Map<Long, Long> counts = categoryRepository.countActiveMealsByCategory().stream()
                .collect(Collectors.toMap(CatalogCategoryRepository.CategoryCount::getCategoryId,
                        CatalogCategoryRepository.CategoryCount::getCount));
        return counts.getOrDefault(categoryId, 0L);
    }

    private static CatalogCategoryDto toDto(CatalogCategory category, long itemCount, List<CatalogCategoryDto> children) {
        return new CatalogCategoryDto(category.getId(), category.getName(), category.getGroup(), category.getSortOrder(),
                category.isActive(), category.isTopLevel(), category.isAssignable(), itemCount, new ArrayList<>(children));
    }
}
