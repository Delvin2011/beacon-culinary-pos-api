package com.beaconculinary.api.inventory;

import lombok.AllArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@AllArgsConstructor
@Service
public class CountSheetCategoryService {
    private final CountSheetCategoryRepository countSheetCategoryRepository;
    private final InventoryMapper inventoryMapper;

    /** id ascending = seeded/created order, which count sheets use for display/print grouping. */
    public List<CountSheetCategoryDto> getAll(boolean includeInactive) {
        return countSheetCategoryRepository.findAll(Sort.by("id")).stream()
                .filter(c -> includeInactive || c.isActive())
                .map(inventoryMapper::toDto)
                .toList();
    }

    @Transactional
    public CountSheetCategoryDto create(CreateCountSheetCategoryRequest request) {
        var name = normalizeName(request.getName());
        ensureNameAvailable(name, null);

        var category = new CountSheetCategory();
        category.setName(name);
        category.setActive(true);
        return inventoryMapper.toDto(countSheetCategoryRepository.save(category));
    }

    /** No delete — ingredients reference categories by FK, so a category is retired by setting
     * active=false. Existing ingredients keep it; it just drops out of the default dropdown. */
    @Transactional
    public CountSheetCategoryDto update(Long id, UpdateCountSheetCategoryRequest request) {
        var category = countSheetCategoryRepository.findById(id)
                .orElseThrow(CountSheetCategoryNotFoundException::new);
        var name = normalizeName(request.getName());
        ensureNameAvailable(name, id);

        category.setName(name);
        category.setActive(request.getActive());
        return inventoryMapper.toDto(countSheetCategoryRepository.save(category));
    }

    // Seeded names are upper case (POULTRY, DRY GOODS, ...) and CSV imports match them
    // case-insensitively, so new names are stored the same way.
    private String normalizeName(String raw) {
        return raw.trim().replaceAll("\\s+", " ").toUpperCase();
    }

    private void ensureNameAvailable(String name, Long selfId) {
        countSheetCategoryRepository.findByNameIgnoreCase(name)
                .filter(existing -> !existing.getId().equals(selfId))
                .ifPresent(existing -> {
                    throw new InvalidInventoryRequestException(
                            "A count sheet category named '" + name + "' already exists.");
                });
    }
}
