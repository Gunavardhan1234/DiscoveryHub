import { Pipe, PipeTransform } from '@angular/core';
import { DomSanitizer, SafeHtml } from '@angular/platform-browser';

/**
 * Renders an Elasticsearch highlight snippet (which wraps matches in
 * {@code <mark>...</mark>}) as safe HTML. All other markup is escaped, so only
 * the {@code <mark>} emphasis tags survive — preventing XSS from source text.
 */
@Pipe({ name: 'markSnippet', standalone: true })
export class MarkSnippetPipe implements PipeTransform {
  constructor(private readonly sanitizer: DomSanitizer) {}

  transform(value: string | null | undefined): SafeHtml {
    if (!value) {
      return this.sanitizer.bypassSecurityTrustHtml('');
    }
    const escaped = value
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/&lt;mark&gt;/g, '<mark>')
      .replace(/&lt;\/mark&gt;/g, '</mark>');
    return this.sanitizer.bypassSecurityTrustHtml(escaped);
  }
}
