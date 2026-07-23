#include "universal-toolcall.h"

#include "log.h"
#include "regex-partial.h"

#include <nlohmann/json.hpp>

using json = nlohmann::ordered_json;

static std::vector<common_universal_toolcall_form> build_registry() {
    std::vector<common_universal_toolcall_form> r;

    // Order matters: forms whose scope_start is a substring of another form's
    // scope_start must come AFTER the longer form. Example: <tool_call> is a
    // substring of <seed:tool_call>, so seed-oss is tried first.

    // MiniMax-M2:
    //   <minimax:tool_call><invoke name="name"><parameter name="k">v</parameter></invoke></minimax:tool_call>
    {
        xml_tool_call_format f{};
        f.scope_start = "<minimax:tool_call>";
        f.tool_start  = "<invoke name=\"";
        f.tool_sep    = "\">";
        f.key_start   = "<parameter name=\"";
        f.key_val_sep = "\">";
        f.val_end     = "</parameter>";
        f.tool_end    = "</invoke>";
        f.scope_end   = "</minimax:tool_call>";
        r.push_back({ "minimax-m2", f, { "<minimax:tool_call>", "<invoke name=\"" } });
    }

    // Seed-OSS:
    //   <seed:tool_call><function=name><parameter=k>v</parameter></function></seed:tool_call>
    {
        xml_tool_call_format f{};
        f.scope_start = "<seed:tool_call>";
        f.tool_start  = "<function=";
        f.tool_sep    = ">";
        f.key_start   = "<parameter=";
        f.key_val_sep = ">";
        f.val_end     = "</parameter>";
        f.tool_end    = "</function>";
        f.scope_end   = "</seed:tool_call>";
        r.push_back({ "seed-oss", f, { "<seed:tool_call>", "<function=" } });
    }

    // Kimi-K2:
    //   <|tool_calls_section_begin|><|tool_call_begin|>fn.id<|tool_call_argument_begin|>{"k":"v"}<|tool_call_end|><|tool_calls_section_end|>
    {
        xml_tool_call_format f{};
        f.scope_start                = "<|tool_calls_section_begin|>";
        f.tool_start                 = "<|tool_call_begin|>";
        f.tool_sep                   = "<|tool_call_argument_begin|>{";
        f.key_start                  = "\"";
        f.key_val_sep                = "\":";
        f.val_end                    = ",";
        f.tool_end                   = "}<|tool_call_end|>";
        f.scope_end                  = "<|tool_calls_section_end|>";
        f.raw_argval                 = false;
        f.last_val_end               = "";
        f.allow_toolcall_in_think    = true;
        r.push_back({ "kimi-k2", f, { "<|tool_calls_section_begin|>", "<|tool_call_begin|>" } });
    }

    // Apriel-1.5:
    //   <tool_calls>[{"name": "n", "arguments": {"k": "v"}}]</tool_calls>
    {
        xml_tool_call_format f{};
        f.scope_start   = "<tool_calls>[";
        f.tool_start    = "{\"name\": \"";
        f.tool_sep      = "\", \"arguments\": {";
        f.key_start     = "\"";
        f.key_val_sep   = "\": ";
        f.val_end       = ", ";
        f.tool_end      = "}, ";
        f.scope_end     = "]</tool_calls>";
        f.raw_argval    = false;
        f.last_val_end  = "";
        f.last_tool_end = "}";
        r.push_back({ "apriel-1.5", f, { "<tool_calls>[", "</tool_calls>" } });
    }

    // Xiaomi-MiMo:
    //   <tool_call>
    //   {"name": "n", "arguments": {"k": "v"}}
    //   </tool_call>
    {
        xml_tool_call_format f{};
        f.scope_start = "";
        f.tool_start  = "<tool_call>\n{\"name\": \"";
        f.tool_sep    = "\", \"arguments\": {";
        f.key_start   = "\"";
        f.key_val_sep = "\": ";
        f.val_end     = ", ";
        f.tool_end    = "}\n</tool_call>";
        f.scope_end   = "";
        f.raw_argval  = false;
        f.last_val_end = "";
        r.push_back({ "xiaomi-mimo", f, { "<tool_call>\n{\"name\": \"" } });
    }

    // Qwen3-Coder:
    //   <tool_call>
    //   <function=name>
    //   <parameter=key>
    //   value
    //   </parameter>
    //   </function>
    //   </tool_call>
    {
        xml_tool_call_format f{};
        f.scope_start      = "<tool_call>";
        f.tool_start       = "<function=";
        f.tool_sep         = ">";
        f.key_start        = "<parameter=";
        f.key_val_sep      = ">";
        f.val_end          = "</parameter>";
        f.tool_end         = "</function>";
        f.scope_end        = "</tool_call>";
        f.raw_argval       = true;
        f.trim_raw_argval  = true;
        r.push_back({ "qwen3-coder", f, { "<tool_call>", "<function=", "</function>" } });
    }

    // GLM-4.5:
    //   <tool_call>function_name
    //   <arg_key>k</arg_key>
    //   <arg_value>v</arg_value>
    //   </tool_call>
    {
        xml_tool_call_format f{};
        f.scope_start  = "";
        f.tool_start   = "<tool_call>";
        f.tool_sep     = "";
        f.key_start    = "<arg_key>";
        f.key_val_sep  = "</arg_key>";
        f.key_val_sep2 = "<arg_value>";
        f.val_end      = "</arg_value>";
        f.tool_end     = "</tool_call>";
        f.scope_end    = "";
        r.push_back({ "glm-4.5", f, { "<tool_call>", "<arg_key>", "<arg_value>" } });
    }

    return r;
}

const std::vector<common_universal_toolcall_form> & common_universal_toolcall_xml_forms() {
    static const std::vector<common_universal_toolcall_form> forms = build_registry();
    return forms;
}

// Try the Hermes-2-Pro / generic JSON family. Handles:
//   <tool_call>{"name":..., "arguments":{...}}</tool_call>
//   <function_call>, <tool>, <tools>, <response>, <json>, <xml>, <JSON> wrappers
//   <function=NAME>{JSON}</function>  <function name="NAME">{JSON}</function>
//   Optional ```json fences
//   Bare {"name": ..., ...}
static bool try_parse_hermes_family(common_chat_msg_parser & builder) {
    static const common_regex open_regex(
        "(?:"
            "(```(?:xml|json)?\\n\\s*)?"
            "("
                "<tool_call>"
                "|<function_call>"
                "|<tool>"
                "|<tools>"
                "|<response>"
                "|<json>"
                "|<xml>"
                "|<JSON>"
            ")?"
            "(\\s*\\{\\s*\"name\")"
        ")"
        "|<function=([^>]+)>"
        "|<function name=\"([^\"]+)\">"
    );

    size_t saved = builder.pos();
    bool matched = false;

    while (auto res = builder.try_find_regex(open_regex)) {
        const auto & block_start = res->groups[1];
        const auto & open_tag    = res->groups[2];

        std::string block_end = block_start.empty() ? "" : "```";
        std::string close_tag;

        if (!res->groups[3].empty()) {
            builder.move_to(res->groups[3].begin);
            close_tag = open_tag.empty() ? "" : "</" + builder.str(open_tag).substr(1);

            auto tool_call = builder.try_consume_json_with_dumped_args({ { "arguments" } });
            if (!tool_call || tool_call->is_partial) {
                builder.move_to(saved);
                return matched;
            }
            if (!builder.add_tool_call(tool_call->value)) {
                builder.move_to(saved);
                return matched;
            }
            matched = true;
            builder.consume_spaces();
            if (!close_tag.empty()) {
                builder.consume_literal(close_tag);
                builder.consume_spaces();
            }
            if (!block_end.empty()) {
                builder.consume_literal(block_end);
                builder.consume_spaces();
            }
        } else {
            std::string function_name = builder.str(res->groups[4]);
            if (function_name.empty()) function_name = builder.str(res->groups[5]);
            if (function_name.empty()) {
                builder.move_to(saved);
                return matched;
            }
            close_tag = "</function>";
            auto arguments = builder.try_consume_json_with_dumped_args({ {} });
            if (!arguments || arguments->is_partial) {
                builder.move_to(saved);
                return matched;
            }
            if (!builder.add_tool_call(function_name, "", arguments->value)) {
                builder.move_to(saved);
                return matched;
            }
            matched = true;
            builder.consume_spaces();
            (void)builder.try_consume_literal(close_tag);
            builder.consume_spaces();
            if (!block_end.empty()) {
                (void)builder.try_consume_literal(block_end);
                builder.consume_spaces();
            }
        }
    }

    return matched;
}

// Try Mistral/LFM style: <|tool_call_start|>[{"name":..}]<|tool_call_end|> or [TOOL_CALLS][{...}]
static bool try_parse_mistral_family(common_chat_msg_parser & builder) {
    size_t saved = builder.pos();

    static const std::vector<std::pair<std::string, std::string>> wraps = {
        { "<|tool_call_start|>", "<|tool_call_end|>" },
        { "[TOOL_CALLS]", "" },
    };

    for (const auto & [open, close] : wraps) {
        auto res = builder.try_find_literal(open);
        if (!res) continue;
        builder.move_to(res->groups[0].end);
        builder.consume_spaces();

        auto parsed = builder.try_consume_json_with_dumped_args({ { "arguments" } });
        if (!parsed || parsed->is_partial) {
            builder.move_to(saved);
            continue;
        }

        bool any = false;
        if (parsed->value.is_array()) {
            for (const auto & tc : parsed->value) {
                if (tc.is_object() && tc.contains("name")) {
                    if (builder.add_tool_call(tc)) any = true;
                }
            }
        } else if (parsed->value.is_object() && parsed->value.contains("name")) {
            any = builder.add_tool_call(parsed->value);
        }

        if (!any) {
            builder.move_to(saved);
            continue;
        }

        builder.consume_spaces();
        if (!close.empty()) (void)builder.try_consume_literal(close);
        builder.consume_spaces();
        return true;
    }

    return false;
}

// Attempt one round of tool-call extraction starting at builder.pos().
// Returns true if a call was added. Does not loop — caller re-invokes to
// pick up additional calls downstream.
static bool parse_one_round(common_chat_msg_parser & builder) {
    size_t pre_calls = builder.result().tool_calls.size();
    size_t saved     = builder.pos();
    const std::string & input = builder.input();

    // XML forms. Skip prelude by locating each form's leftmost required
    // marker and emitting the text before it as content.
    size_t committed_up_to = saved;
    for (const auto & form : common_universal_toolcall_xml_forms()) {
        size_t leftmost = std::string::npos;
        bool has_markers = !form.required_markers.empty();
        for (const auto & m : form.required_markers) {
            auto p = input.find(m, committed_up_to);
            if (p == std::string::npos) { has_markers = false; break; }
            if (p < leftmost) leftmost = p;
        }
        if (!has_markers) continue;

        if (leftmost > committed_up_to) {
            builder.add_content(input.substr(committed_up_to, leftmost - committed_up_to));
            committed_up_to = leftmost;
        }
        builder.move_to(committed_up_to);

        try {
            bool consumed = builder.try_consume_xml_tool_calls(form.spec);
            bool added    = builder.result().tool_calls.size() > pre_calls;
            if (consumed && added) {
                LOG_DBG("universal-toolcall: matched %s\n", form.name.c_str());
                return true;
            }
        } catch (const std::exception & e) {
            LOG_DBG("universal-toolcall: %s raised %s\n", form.name.c_str(), e.what());
        }
        builder.move_to(committed_up_to);
    }
    builder.move_to(committed_up_to);

    if (try_parse_mistral_family(builder)) return true;
    builder.move_to(committed_up_to);

    if (try_parse_hermes_family(builder)) return true;
    builder.move_to(committed_up_to);

    return false;
}

bool common_universal_toolcall_parse(common_chat_msg_parser & builder) {
    if (!builder.syntax().parse_tool_calls) {
        return false;
    }

    bool any = false;
    while (true) {
        size_t before = builder.pos();
        size_t pre_calls = builder.result().tool_calls.size();
        if (!parse_one_round(builder)) break;
        if (builder.result().tool_calls.size() <= pre_calls) break;
        if (builder.pos() == before) break;
        any = true;
    }
    return any;
}

bool common_universal_toolcall_detect(const std::string & input, size_t from) {
    static const std::vector<std::string> markers = {
        "<tool_call>",
        "<minimax:tool_call>",
        "<seed:tool_call>",
        "<tool_calls>",
        "<|tool_calls_section_begin|>",
        "<|tool_call_start|>",
        "[TOOL_CALLS]",
        "<function_call>",
        "<function=",
        "<function name=\"",
    };
    for (const auto & m : markers) {
        if (input.find(m, from) != std::string::npos) return true;
    }
    auto p = input.find_first_not_of(" \t\r\n", from);
    if (p != std::string::npos && input[p] == '{') {
        auto name_pos = input.find("\"name\"", p);
        if (name_pos != std::string::npos && name_pos - p < 32) return true;
    }
    return false;
}
