package com.magnetplatform.api.controller;

import com.magnetplatform.api.dto.Dtos.ExportView;
import com.magnetplatform.application.ExportService;
import com.magnetplatform.application.ExportService.OpenedExport;
import com.magnetplatform.application.JwtPrincipal;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tasks/{taskId}/exports")
public class ExportController {

    private final ExportService exportService;

    public ExportController(ExportService exportService) {
        this.exportService = exportService;
    }

    @GetMapping
    public List<ExportView> list(Authentication auth, @PathVariable UUID taskId) {
        JwtPrincipal caller = TaskController.principal(auth);
        return exportService.listOwned(caller.userId(), caller.admin(), taskId).stream()
                .map(ExportView::from)
                .toList();
    }

    @PostMapping
    public List<ExportView> export(Authentication auth, @PathVariable UUID taskId) {
        JwtPrincipal caller = TaskController.principal(auth);
        return exportService.exportOwned(caller.userId(), caller.admin(), taskId).stream()
                .map(ExportView::from)
                .toList();
    }

    @GetMapping("/{exportId}/content")
    public ResponseEntity<Resource> content(Authentication auth,
                                            @PathVariable UUID taskId,
                                            @PathVariable UUID exportId) {
        JwtPrincipal caller = TaskController.principal(auth);
        OpenedExport opened = exportService.openOwned(caller.userId(), caller.admin(), taskId, exportId);
        Path filenamePath = Path.of(opened.export().getRelativePath()).getFileName();
        String filename = filenamePath == null ? opened.export().getRelativePath() : filenamePath.toString();
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(filename, StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentLength(opened.export().getBytes())
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new FileSystemResource(opened.path()));
    }
}
