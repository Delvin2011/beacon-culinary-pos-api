package com.beaconculinary.api.board;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class PublicBoardDto {
    private List<PublicOrderDto> orders;
}
