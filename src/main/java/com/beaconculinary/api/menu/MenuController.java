package com.beaconculinary.api.menu;

import com.beaconculinary.api.common.ErrorDto;
import lombok.AllArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@AllArgsConstructor
@RestController
public class MenuController {
    private final DailyPlanningService dailyPlanningService;
    private final AlwaysOnMenuService alwaysOnMenuService;

    @GetMapping("/menu/today")
    public MenuTodayResponseDto getTodayMenu(@RequestParam(required = false) String period) {
        // Today's always-on options are created first, outside the menu's read transaction.
        alwaysOnMenuService.ensureAlwaysOnOptionsToday();
        return dailyPlanningService.getTodayMenu(period);
    }

    @ExceptionHandler(InvalidMenuRequestException.class)
    public ResponseEntity<ErrorDto> handleInvalidRequest(InvalidMenuRequestException ex) {
        return ResponseEntity.badRequest().body(new ErrorDto(ex.getMessage()));
    }
}
