package dev.proppilot.rag;

/** A lease document as the list endpoint shows it. The PDF itself is at {@code /api/lease-documents/{code}/pdf}. */
public record LeaseDocumentView(String code, String title, String language, String unitCode, String tenantNameEn,
                                String tenantNameAr, int pageCount, int chunkCount) {
}
