package com.backtoback.member.skill.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.backtoback.member.skill.dto.InternalTagLevelResponse;
import com.backtoback.member.skill.service.TagLevelService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/internal/v1/members/{memberId}/tag-levels")
@RequiredArgsConstructor
public class InternalTagLevelController {

    private final TagLevelService tagLevelService;

    /**
     * 1.7.2 태그 레벨 조회 (내부) — 진입한 태그의 레벨만 반환한다. 진입하지 않은 태그는 judge가 {@code LV0}으로 본다.
     */
    @GetMapping
    public ResponseEntity<InternalTagLevelResponse> getTagLevels(@PathVariable Long memberId) {
        return ResponseEntity
            .ok(InternalTagLevelResponse.from(memberId, tagLevelService.getEnteredTagLevels(memberId)));
    }
}
