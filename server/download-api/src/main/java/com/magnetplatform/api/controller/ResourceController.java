package com.magnetplatform.api.controller;

import com.magnetplatform.api.dto.Dtos.ResourceDetailView;
import com.magnetplatform.application.JwtPrincipal;
import com.magnetplatform.application.ParseService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** 已解析资源的目录树 / 文件清单（规格 §4.2-4） */
@RestController
@RequestMapping("/api/v1/resources")
public class ResourceController {

    private final ParseService parseService;

    public ResourceController(ParseService parseService) {
        this.parseService = parseService;
    }

    @GetMapping("/{id}/files")
    public ResourceDetailView files(Authentication auth, @PathVariable UUID id) {
        JwtPrincipal caller = (JwtPrincipal) auth.getPrincipal();
        return ResourceDetailView.from(parseService.getResourceFiles(caller.userId(), caller.admin(), id));
    }
}
