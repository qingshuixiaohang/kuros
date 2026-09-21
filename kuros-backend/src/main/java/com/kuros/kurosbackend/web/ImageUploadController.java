package com.kuros.kurosbackend.web;

import cn.dev33.satoken.stp.StpUtil;
import com.kuros.kurosbackend.shared.api.ApiResponse;
import com.kuros.kurosbackend.api.ImageUploadResponse;
import com.kuros.kurosbackend.storage.MediaAssetService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/files")
public class ImageUploadController {

    private final MediaAssetService imageStorageService;

    public ImageUploadController(MediaAssetService imageStorageService) {
        this.imageStorageService = imageStorageService;
    }

    @PostMapping(value = "/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<ImageUploadResponse>> upload(@RequestPart("file") MultipartFile file) {
        ImageUploadResponse result = imageStorageService.store(file, StpUtil.getLoginIdAsString());
        return ResponseEntity.status(HttpStatus.CREATED).body(new ApiResponse<>(result, null));
    }

    @DeleteMapping("/images/{assetId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String assetId) {
        imageStorageService.delete(assetId, StpUtil.getLoginIdAsString());
    }
}
