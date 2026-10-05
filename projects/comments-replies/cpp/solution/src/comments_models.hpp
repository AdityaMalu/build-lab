#pragma once

#include <optional>
#include <stdexcept>
#include <string>
#include <vector>

struct Comment {
    std::string id;
    std::string postId;
    std::optional<std::string> parentId;  // empty for top-level comments
    std::optional<std::string> author;    // empty once deleted
    std::string text;                     // "[deleted]" once deleted
    long long seq = 0;
    int depth = 0;
    bool deleted = false;
};

struct CommentNode {
    Comment comment;
    std::vector<CommentNode> replies;
};

struct InvalidState : std::runtime_error {
    using std::runtime_error::runtime_error;
};

struct PermissionDenied : std::runtime_error {
    using std::runtime_error::runtime_error;
};

constexpr std::size_t kMaxLength = 500;
constexpr int kMaxDepth = 3;
