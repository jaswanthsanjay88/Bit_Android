// Universal tool-call parser. Works for every GGUF model — whether the chat
// template supports tools natively or not. Walks a registry of known XML and
// JSON tool-call forms and delegates to the matching sub-parser.

#pragma once

#include "chat-parser.h"
#include "chat-parser-xml-toolcall.h"

#include <string>
#include <vector>

struct common_universal_toolcall_form {
    std::string                name;
    xml_tool_call_format       spec;
    // Literals that MUST appear in the input for this form to be tried. Guards
    // against loose grammars (e.g. GLM-4.5 with an empty tool_sep) matching
    // unrelated content when their inner markers are absent.
    std::vector<std::string>   required_markers;
};

// Priority-ordered list of XML-style tool-call formats covering every known
// GGUF model family. First match wins.
const std::vector<common_universal_toolcall_form> & common_universal_toolcall_xml_forms();

// Try every recognised tool-call format at the builder's current position.
// Returns true if at least one tool call was parsed. Restores builder state
// if nothing matched.
bool common_universal_toolcall_parse(common_chat_msg_parser & builder);

// Scan-only variant: does not mutate builder. Returns true if any form
// would match at the given position.
bool common_universal_toolcall_detect(const std::string & input, size_t from = 0);
