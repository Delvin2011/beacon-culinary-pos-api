package com.beaconculinary.api.devices;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** POS till registration (WS1-1b). ADMIN only, through the /admin/** rule in AdminSecurityRules. */
@RestController
@RequestMapping("/admin/devices")
public class DeviceController {
    private final DeviceService deviceService;

    public DeviceController(DeviceService deviceService) {
        this.deviceService = deviceService;
    }

    @GetMapping("/config")
    public DeviceDtos.Config config() {
        return deviceService.config();
    }

    @GetMapping
    public List<DeviceDtos.Device> list() {
        return deviceService.list();
    }

    /** Must be called from the browser being registered: the deviceToken cookie is set on it. */
    @PostMapping
    public ResponseEntity<DeviceDtos.Device> register(@RequestBody DeviceRequests.Register request,
                                                      HttpServletRequest http, HttpServletResponse response) {
        return ResponseEntity.status(HttpStatus.CREATED).body(deviceService.register(request, http, response));
    }

    @PutMapping("/{id}")
    public DeviceDtos.Device update(@PathVariable String id, @RequestBody DeviceRequests.Update request, HttpServletRequest http) {
        return deviceService.update(id, request, http);
    }

    @PutMapping("/{id}/credentials/{credentialId}")
    public DeviceDtos.Device updateLabel(@PathVariable String id, @PathVariable String credentialId,
                                         @RequestBody DeviceRequests.Label request, HttpServletRequest http) {
        return deviceService.updateLabel(id, credentialId, request, http);
    }

    @PostMapping("/{id}/revoke")
    public DeviceDtos.Device revokeDevice(@PathVariable String id,
                                          @RequestBody(required = false) DeviceRequests.Revoke request,
                                          HttpServletRequest http) {
        return deviceService.revokeDevice(id, request, http);
    }

    @PostMapping("/{id}/credentials/{credentialId}/revoke")
    public DeviceDtos.Device revokeCredential(@PathVariable String id, @PathVariable String credentialId,
                                              @RequestBody(required = false) DeviceRequests.Revoke request,
                                              HttpServletRequest http) {
        return deviceService.revokeCredential(id, credentialId, request, http);
    }
}
