package com.beaconculinary.api.board;

import lombok.AllArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@AllArgsConstructor
@RestController
@RequestMapping("/public/board")
public class PublicBoardController {
    private final PublicBoardService publicBoardService;

    @GetMapping("/today")
    public PublicBoardDto getTodayBoard() {
        return publicBoardService.getTodayBoard();
    }

    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return publicBoardService.subscribe();
    }
}
