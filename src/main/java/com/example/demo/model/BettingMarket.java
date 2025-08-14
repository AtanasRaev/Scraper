package com.example.demo.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Represents a betting market type (e.g., 1X2, Over/Under, Handicap)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BettingMarket {
    private String marketId;
    private String marketType;
    private List<BettingSelection> selections;
}