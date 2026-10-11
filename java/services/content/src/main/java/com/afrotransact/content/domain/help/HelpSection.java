package com.afrotransact.content.domain.help;

/**
 * One heading and its text on a help page.
 *
 * @param heading short heading
 * @param body    Markdown (paragraphs, lists, links)
 */
public record HelpSection(String heading, String body) {
}
