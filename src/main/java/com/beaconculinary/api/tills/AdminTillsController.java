package com.beaconculinary.api.tills;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** O2: GET /admin/tills. ADMIN only, through the /admin/** rule in AdminSecurityRules. */
@RestController
public class AdminTillsController {
    private final AdminTillsService adminTillsService;

    public AdminTillsController(AdminTillsService adminTillsService) {
        this.adminTillsService = adminTillsService;
    }

    @GetMapping("/admin/tills")
    public List<AdminTillDto> tills() {
        return adminTillsService.tills();
    }
}
