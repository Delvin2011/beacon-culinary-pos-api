package com.beaconculinary.api.menu;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@AllArgsConstructor
public class ComponentCatalogService {
    private final ComponentCatalogRepository componentCatalogRepository;
    private final MealMapper mealMapper;

    public List<ComponentCatalogDto> getAll() {
        return componentCatalogRepository.findAll().stream().map(mealMapper::toDto).toList();
    }

    public ComponentCatalogDto create(CreateComponentCatalogRequest request) {
        var entity = new ComponentCatalog();
        entity.setName(request.getName());
        entity.setExtraPrice(request.getExtraPrice());
        componentCatalogRepository.save(entity);
        return mealMapper.toDto(entity);
    }

    public ComponentCatalogDto update(Long id, UpdateComponentCatalogRequest request) {
        var entity = componentCatalogRepository.findById(id).orElseThrow(ComponentCatalogNotFoundException::new);
        entity.setName(request.getName());
        entity.setExtraPrice(request.getExtraPrice());
        entity.setActive(request.isActive());
        componentCatalogRepository.save(entity);
        return mealMapper.toDto(entity);
    }
}
