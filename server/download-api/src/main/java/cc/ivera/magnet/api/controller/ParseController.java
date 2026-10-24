package cc.ivera.magnet.api.controller;

import cc.ivera.magnet.api.dto.Dtos.PageView;
import cc.ivera.magnet.api.dto.Dtos.ParseJobView;
import cc.ivera.magnet.api.dto.Dtos.SubmitMagnetRequest;
import cc.ivera.magnet.application.JwtPrincipal;
import cc.ivera.magnet.application.Page;
import cc.ivera.magnet.application.ParseService;
import cc.ivera.magnet.domain.common.DomainException;
import cc.ivera.magnet.domain.common.ErrorCodes;
import cc.ivera.magnet.domain.parse.ParseJob;
import cc.ivera.magnet.domain.parse.ParseStatus;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.UUID;

/** 资源解析（规格 §4.2：先解析后下载，历史可查看可重试） */
@RestController
@RequestMapping("/api/v1/parses")
public class ParseController {

    private final ParseService parseService;

    public ParseController(ParseService parseService) {
        this.parseService = parseService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ParseJobView submitMagnet(Authentication auth, @Valid @RequestBody SubmitMagnetRequest request) {
        return ParseJobView.from(parseService.submitUri(principal(auth).userId(), request.uri()));
    }

    @PostMapping("/upload")
    @ResponseStatus(HttpStatus.CREATED)
    public ParseJobView submitTorrent(Authentication auth, @RequestParam("file") MultipartFile file) {
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new DomainException(ErrorCodes.VALIDATION, "读取上传文件失败");
        }
        return ParseJobView.from(parseService.submitTorrent(principal(auth).userId(),
                file.getOriginalFilename(), bytes));
    }

    @GetMapping
    public PageView<ParseJobView> list(Authentication auth,
                                       @RequestParam(defaultValue = "1") int page,
                                       @RequestParam(defaultValue = "30") int size,
                                       @RequestParam(required = false) String status) {
        Page<ParseJob> result = parseService.listParses(principal(auth).userId(), parseStatus(status), page, size);
        return PageView.from(result, ParseJobView::from);
    }

    @GetMapping("/{id}")
    public ParseJobView get(Authentication auth, @PathVariable UUID id) {
        JwtPrincipal caller = principal(auth);
        return ParseJobView.from(parseService.getParse(caller.userId(), caller.admin(), id));
    }

    @PostMapping("/{id}/retry")
    public ParseJobView retry(Authentication auth, @PathVariable UUID id) {
        JwtPrincipal caller = principal(auth);
        return ParseJobView.from(parseService.retry(caller.userId(), caller.admin(), id));
    }

    static JwtPrincipal principal(Authentication auth) {
        return (JwtPrincipal) auth.getPrincipal();
    }

    private static ParseStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return ParseStatus.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new DomainException(ErrorCodes.VALIDATION, "未知解析状态: " + status);
        }
    }
}
