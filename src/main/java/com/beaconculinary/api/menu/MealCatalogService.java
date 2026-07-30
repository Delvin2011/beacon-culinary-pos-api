package com.beaconculinary.api.menu;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;

@Service
@AllArgsConstructor
public class MealCatalogService {
    private final MealCatalogRepository mealCatalogRepository;
    private final ComponentCatalogRepository componentCatalogRepository;
    private final MealMapper mealMapper;

    public List<MealCatalogDto> getAll() {
        return mealCatalogRepository.findAllWithComponentsBy().stream().map(mealMapper::toDto).toList();
    }

    @Transactional
    public MealCatalogDto create(CreateMealCatalogRequest request) {
        var entity = new MealCatalog();
        entity.setName(request.getName());
        entity.setDescription(request.getDescription());
        entity.setPrice(request.getPrice());
        applyComponents(entity, request.getComponentIds());
        mealCatalogRepository.save(entity);
        return mealMapper.toDto(entity);
    }

    @Transactional
    public MealCatalogDto update(Long id, UpdateMealCatalogRequest request) {
        var entity = mealCatalogRepository.findWithComponentsById(id).orElseThrow(MealCatalogNotFoundException::new);
        entity.setName(request.getName());
        entity.setDescription(request.getDescription());
        entity.setPrice(request.getPrice());
        entity.setActive(request.isActive());
        entity.getComponents().clear();
        applyComponents(entity, request.getComponentIds());
        mealCatalogRepository.save(entity);
        return mealMapper.toDto(entity);
    }

    private void applyComponents(MealCatalog mealCatalog, List<Long> componentIds) {
        if (componentIds == null || componentIds.isEmpty()) return;

        var components = componentCatalogRepository.findAllByIdIn(componentIds);
        if (components.size() != new HashSet<>(componentIds).size()) {
            throw new InvalidMenuRequestException("One or more componentIds do not exist.");
        }

        for (var component : components) {
            var link = new MealCatalogComponent();
            link.setMealCatalog(mealCatalog);
            link.setComponentCatalog(component);
            mealCatalog.getComponents().add(link);
        }
    }
}
