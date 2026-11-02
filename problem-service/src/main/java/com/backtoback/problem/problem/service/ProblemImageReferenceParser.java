package com.backtoback.problem.problem.service;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.Image;
import org.commonmark.parser.Parser;
import org.springframework.stereotype.Component;

import com.backtoback.problem.global.error.ProblemErrorCode;
import com.backtoback.problem.global.exception.ProblemException;

/** Markdown의 이미지 참조를 중복 없이 추출한다(API 명세서 2.3.0). */
@Component
public class ProblemImageReferenceParser {
    private static final int MAX_IMAGES = 10;
    private final Parser parser = Parser.builder().build();

    /** 본문의 서로 다른 이미지 ID를 잠금 순서인 오름차순으로 반환한다. */
    public List<Long> parse(String... markdownParts) {
        Set<Long> ids = new TreeSet<>();
        for (String part : markdownParts) {
            if (part == null) {
                throw new ProblemException(ProblemErrorCode.PROBLEM_IMAGE_INVALID);
            }
            parser.parse(part).accept(new AbstractVisitor() {
                @Override
                public void visit(Image image) {
                    try {
                        String destination = image.getDestination();
                        if (destination == null || !destination.startsWith("image:")) {
                            throw new NumberFormatException();
                        }
                        String value = destination.substring("image:".length());
                        if (!value.matches("[0-9]+")) {
                            throw new NumberFormatException();
                        }
                        long id = Long.parseLong(value);
                        if (id <= 0) {
                            throw new NumberFormatException();
                        }
                        ids.add(id);
                    } catch (NumberFormatException exception) {
                        throw new ProblemException(ProblemErrorCode.PROBLEM_IMAGE_INVALID);
                    }
                    if (ids.size() > MAX_IMAGES) {
                        throw new ProblemException(ProblemErrorCode.PROBLEM_IMAGE_INVALID);
                    }
                    visitChildren(image);
                }
            });
        }
        return List.copyOf(ids);
    }
}
