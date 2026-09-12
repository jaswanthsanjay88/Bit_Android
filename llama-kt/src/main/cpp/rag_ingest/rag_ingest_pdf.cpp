#include "rag_ingest.h"

#include "third_party/pdfium/include/fpdfview.h"
#include "third_party/pdfium/include/fpdf_text.h"

#include <dlfcn.h>
#include <mutex>
#include <string>
#include <vector>

namespace {

typedef void (*pfn_FPDF_InitLibraryWithConfig)(const FPDF_LIBRARY_CONFIG* config);
typedef FPDF_DOCUMENT (*pfn_FPDF_LoadMemDocument)(const void* data_buf, int size, FPDF_BYTESTRING password);
typedef unsigned long (*pfn_FPDF_GetLastError)();
typedef int (*pfn_FPDF_GetPageCount)(FPDF_DOCUMENT document);
typedef void (*pfn_FPDF_CloseDocument)(FPDF_DOCUMENT document);
typedef FPDF_PAGE (*pfn_FPDF_LoadPage)(FPDF_DOCUMENT document, int page_index);
typedef void (*pfn_FPDF_ClosePage)(FPDF_PAGE page);
typedef FPDF_TEXTPAGE (*pfn_FPDFText_LoadPage)(FPDF_PAGE page);
typedef void (*pfn_FPDFText_ClosePage)(FPDF_TEXTPAGE text_page);
typedef int (*pfn_FPDFText_CountChars)(FPDF_TEXTPAGE text_page);
typedef int (*pfn_FPDFText_GetText)(FPDF_TEXTPAGE text_page, int start_index, int count, unsigned short* result);

struct PdfiumApi {
    void* handle = nullptr;
    pfn_FPDF_InitLibraryWithConfig fn_InitLibraryWithConfig = nullptr;
    pfn_FPDF_LoadMemDocument fn_LoadMemDocument = nullptr;
    pfn_FPDF_GetLastError fn_GetLastError = nullptr;
    pfn_FPDF_GetPageCount fn_GetPageCount = nullptr;
    pfn_FPDF_CloseDocument fn_CloseDocument = nullptr;
    pfn_FPDF_LoadPage fn_LoadPage = nullptr;
    pfn_FPDF_ClosePage fn_ClosePage = nullptr;
    pfn_FPDFText_LoadPage fn_LoadTextPage = nullptr;
    pfn_FPDFText_ClosePage fn_CloseTextPage = nullptr;
    pfn_FPDFText_CountChars fn_CountChars = nullptr;
    pfn_FPDFText_GetText fn_GetText = nullptr;
};

static PdfiumApi g_pdfium;
static std::once_flag g_pdfium_init_once;
static bool g_pdfium_init_ok = false;

void init_pdfium_once() {
    std::call_once(g_pdfium_init_once, []() {
        void* handle = dlopen("libpdfium.so", RTLD_LOCAL | RTLD_LAZY);
        if (!handle) {
            return;
        }
        g_pdfium.handle = handle;
        g_pdfium.fn_InitLibraryWithConfig = (pfn_FPDF_InitLibraryWithConfig)dlsym(handle, "FPDF_InitLibraryWithConfig");
        g_pdfium.fn_LoadMemDocument       = (pfn_FPDF_LoadMemDocument)dlsym(handle, "FPDF_LoadMemDocument");
        g_pdfium.fn_GetLastError          = (pfn_FPDF_GetLastError)dlsym(handle, "FPDF_GetLastError");
        g_pdfium.fn_GetPageCount          = (pfn_FPDF_GetPageCount)dlsym(handle, "FPDF_GetPageCount");
        g_pdfium.fn_CloseDocument         = (pfn_FPDF_CloseDocument)dlsym(handle, "FPDF_CloseDocument");
        g_pdfium.fn_LoadPage              = (pfn_FPDF_LoadPage)dlsym(handle, "FPDF_LoadPage");
        g_pdfium.fn_ClosePage             = (pfn_FPDF_ClosePage)dlsym(handle, "FPDF_ClosePage");
        g_pdfium.fn_LoadTextPage          = (pfn_FPDFText_LoadPage)dlsym(handle, "FPDFText_LoadPage");
        g_pdfium.fn_CloseTextPage         = (pfn_FPDFText_ClosePage)dlsym(handle, "FPDFText_ClosePage");
        g_pdfium.fn_CountChars            = (pfn_FPDFText_CountChars)dlsym(handle, "FPDFText_CountChars");
        g_pdfium.fn_GetText               = (pfn_FPDFText_GetText)dlsym(handle, "FPDFText_GetText");

        if (!g_pdfium.fn_InitLibraryWithConfig || !g_pdfium.fn_LoadMemDocument ||
            !g_pdfium.fn_GetLastError || !g_pdfium.fn_GetPageCount ||
            !g_pdfium.fn_CloseDocument || !g_pdfium.fn_LoadPage ||
            !g_pdfium.fn_ClosePage || !g_pdfium.fn_LoadTextPage ||
            !g_pdfium.fn_CloseTextPage || !g_pdfium.fn_CountChars ||
            !g_pdfium.fn_GetText) {
            return;
        }

        FPDF_LIBRARY_CONFIG cfg{};
        cfg.version = 2;
        cfg.m_pUserFontPaths = nullptr;
        cfg.m_pIsolate = nullptr;
        cfg.m_v8EmbedderSlot = 0;
        g_pdfium.fn_InitLibraryWithConfig(&cfg);
        g_pdfium_init_ok = true;
    });
}

void append_utf16le_to_utf8(const unsigned short* src, int len, std::string& out) {
    for (int i = 0; i < len; ++i) {
        uint32_t cp = src[i];
        if (cp == 0) break;
        if (cp >= 0xD800 && cp <= 0xDBFF && i + 1 < len) {
            uint32_t low = src[i + 1];
            if (low >= 0xDC00 && low <= 0xDFFF) {
                cp = 0x10000 + (((cp - 0xD800) << 10) | (low - 0xDC00));
                ++i;
            }
        }
        if (cp < 0x80) out.push_back((char) cp);
        else if (cp < 0x800) {
            out.push_back((char) (0xC0 | (cp >> 6)));
            out.push_back((char) (0x80 | (cp & 0x3F)));
        } else if (cp < 0x10000) {
            out.push_back((char) (0xE0 | (cp >> 12)));
            out.push_back((char) (0x80 | ((cp >> 6) & 0x3F)));
            out.push_back((char) (0x80 | (cp & 0x3F)));
        } else {
            out.push_back((char) (0xF0 | (cp >> 18)));
            out.push_back((char) (0x80 | ((cp >> 12) & 0x3F)));
            out.push_back((char) (0x80 | ((cp >> 6) & 0x3F)));
            out.push_back((char) (0x80 | (cp & 0x3F)));
        }
    }
}

void normalize_spaces(std::string& s) {
    std::string out;
    out.reserve(s.size());
    bool prev_space = true;
    bool prev_nl = true;
    for (size_t i = 0; i < s.size(); ++i) {
        unsigned char c = (unsigned char) s[i];
        if (c == '\n') {
            if (!prev_nl) { out.push_back('\n'); prev_nl = true; prev_space = true; }
        } else if (c == '\r' || c == '\t' || c == ' ') {
            if (!prev_space) { out.push_back(' '); prev_space = true; }
        } else if (c < 0x20) {
            continue;
        } else {
            out.push_back((char) c);
            prev_space = false;
            prev_nl = false;
        }
    }
    while (!out.empty() && (out.back() == ' ' || out.back() == '\n')) out.pop_back();
    s.swap(out);
}

}

int rag_ingest_extract_pdf(const uint8_t* bytes, size_t len, std::string& out) {
    init_pdfium_once();
    if (!g_pdfium_init_ok) return RAG_INGEST_ERR_INTERNAL;

    FPDF_DOCUMENT doc = g_pdfium.fn_LoadMemDocument(bytes, (int) len, nullptr);
    if (!doc) {
        unsigned long err = g_pdfium.fn_GetLastError();
        if (err == FPDF_ERR_PASSWORD) return RAG_INGEST_ERR_UNSUPPORTED;
        return RAG_INGEST_ERR_PARSE;
    }

    int n_pages = g_pdfium.fn_GetPageCount(doc);
    if (n_pages <= 0) { g_pdfium.fn_CloseDocument(doc); return RAG_INGEST_ERR_EMPTY; }

    out.clear();
    out.reserve((size_t) n_pages * 1024);

    std::vector<unsigned short> buf;

    for (int p = 0; p < n_pages; ++p) {
        FPDF_PAGE page = g_pdfium.fn_LoadPage(doc, p);
        if (!page) continue;
        FPDF_TEXTPAGE tp = g_pdfium.fn_LoadTextPage(page);
        if (!tp) { g_pdfium.fn_ClosePage(page); continue; }

        int n_chars = g_pdfium.fn_CountChars(tp);
        if (n_chars > 0) {
            buf.resize((size_t) n_chars + 1);
            int got = g_pdfium.fn_GetText(tp, 0, n_chars, buf.data());
            if (got > 0) append_utf16le_to_utf8(buf.data(), got, out);
        }
        if (!out.empty() && out.back() != '\n') out.push_back('\n');

        g_pdfium.fn_CloseTextPage(tp);
        g_pdfium.fn_ClosePage(page);
    }

    g_pdfium.fn_CloseDocument(doc);

    normalize_spaces(out);
    return out.empty() ? RAG_INGEST_ERR_EMPTY : RAG_INGEST_OK;
}
