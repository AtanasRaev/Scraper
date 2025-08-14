package com.example.demo.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents a betting selection (e.g., Home, Draw, Away) with its odds
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BettingSelection {
    private String selectionName;
    private double odds;
}