package com.beaconculinary.api.shifts;

import com.beaconculinary.api.auth.AuthService;
import com.beaconculinary.api.users.Role;
import lombok.AllArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

@Service
@AllArgsConstructor
public class ShiftService {
    private final ShiftRepository shiftRepository;
    private final AuthService authService;
    private final ShiftMapper shiftMapper;
    private final Clock clock;

    @Transactional
    public ShiftDto openShift(OpenShiftRequest request) {
        var currentUser = authService.getCurrentUser();

        shiftRepository.findFirstByCashierIdAndStatus(currentUser.getId(), ShiftStatus.OPEN)
                .ifPresent(s -> { throw new ShiftAlreadyOpenException(); });

        var shift = new Shift();
        shift.setCashier(currentUser);
        shift.setOpeningFloat(request.getOpeningFloat());
        shift.setStatus(ShiftStatus.OPEN);
        shiftRepository.save(shift);

        return shiftMapper.toDto(shift);
    }

    @Transactional
    public ShiftDto closeShift(Long id) {
        var shift = shiftRepository.findById(id).orElseThrow(ShiftNotFoundException::new);
        var currentUser = authService.getCurrentUser();

        var isOwner = shift.getCashier().getId().equals(currentUser.getId());
        if (!isOwner && currentUser.getRole() != Role.ADMIN) {
            throw new AccessDeniedException("Cannot close another cashier's shift.");
        }

        shift.setStatus(ShiftStatus.CLOSED);
        shift.setClosedAt(LocalDateTime.now(clock));
        shiftRepository.save(shift);

        return shiftMapper.toDto(shift);
    }

    public ShiftDto getCurrentShift() {
        var currentUser = authService.getCurrentUser();
        var shift = shiftRepository.findFirstByCashierIdAndStatus(currentUser.getId(), ShiftStatus.OPEN)
                .orElseThrow(ShiftNotFoundException::new);

        return shiftMapper.toDto(shift);
    }
}
