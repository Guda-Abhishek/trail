package com.AccountReceivableManagement.controller.receivable;

import com.AccountReceivableManagement.dto.centralizeddto.ApiResponse;
import com.AccountReceivableManagement.dto.receivable.ReceivableResponseDto;
import com.AccountReceivableManagement.entity_enums.receivable.ReceivableStatus;
import com.AccountReceivableManagement.service_interface.receivable.ReceivableService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Read-only receivable monitoring for Finance. Receivables are created by
 * invoice dispatch; nothing here writes.
 */
@RestController
@RequestMapping("/api/v1/receivables")
@RequiredArgsConstructor
public class ReceivableController {

    private final ReceivableService receivableService;

    /**
     * Receivables with a balance owed (outstandingBalance &gt; 0), earliest
     * due first. Zero-balance receivables are excluded but kept.
     */
    @GetMapping("/outstanding")
    public ResponseEntity<ApiResponse<List<ReceivableResponseDto>>> getOutstandingReceivables(
            @RequestParam(required = false) UUID clientId,
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) ReceivableStatus status
    ) {

        return ResponseEntity.ok(
                ApiResponse.<List<ReceivableResponseDto>>builder()
                        .success(true)
                        .message("Outstanding receivables retrieved successfully.")
                        .data(receivableService.getOutstandingReceivables(clientId, projectId, status))
                        .build()
        );
    }

    /**
     * One receivable in any status, including settled.
     */
    @GetMapping("/{receivableId}")
    public ResponseEntity<ApiResponse<ReceivableResponseDto>> getReceivableById(
            @PathVariable UUID receivableId
    ) {

        return ResponseEntity.ok(
                ApiResponse.<ReceivableResponseDto>builder()
                        .success(true)
                        .message("Receivable retrieved successfully.")
                        .data(receivableService.getReceivableById(receivableId))
                        .build()
        );
    }
}
