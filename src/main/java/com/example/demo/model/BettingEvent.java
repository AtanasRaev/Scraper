package com.example.demo.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Represents a betting event with its markets and selections
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BettingEvent {
    private String eventId;
    private String matchName;
    private LocalDateTime startTime;
    private List<BettingMarket> markets;
}
