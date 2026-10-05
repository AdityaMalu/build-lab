#pragma once

#include <map>
#include <mutex>
#include <unordered_map>

#include "comments_models.hpp"

class CommentService {
public:
    Comment addComment(const std::string& postId, const std::string& author, const std::string& text) {
        requireNonBlank(postId, "postId");
        requireNonBlank(author, "author");
        std::string body = clean(text);
        std::lock_guard<std::mutex> lock(mu_);
        return insert(postId, std::nullopt, author, body, 0);
    }

    Comment reply(const std::string& parentId, const std::string& author, const std::string& text) {
        requireNonBlank(author, "author");
        std::string body = clean(text);
        std::lock_guard<std::mutex> lock(mu_);
        auto it = byId_.find(parentId);
        if (it == byId_.end()) throw std::out_of_range("no comment " + parentId);
        const Comment& parent = it->second;
        if (parent.deleted) throw InvalidState("cannot reply to a deleted comment");
        if (parent.depth >= kMaxDepth) throw InvalidState("max depth reached");
        return insert(parent.postId, parent.id, author, body, parent.depth + 1);
    }

    std::vector<CommentNode> getThread(const std::string& postId) {
        std::lock_guard<std::mutex> lock(mu_);
        return build(rootKey(postId));
    }

    void remove(const std::string& commentId, const std::string& requester) {
        std::lock_guard<std::mutex> lock(mu_);
        auto it = byId_.find(commentId);
        if (it == byId_.end()) throw std::out_of_range("no comment " + commentId);
        Comment& c = it->second;
        if (c.deleted) return;
        if (c.author != requester) throw PermissionDenied("only the author may delete");
        c.author.reset();
        c.text = "[deleted]";
        c.deleted = true;
    }

    int countVisible(const std::string& postId) {
        std::lock_guard<std::mutex> lock(mu_);
        int n = 0;
        for (const auto& [id, c] : byId_)
            if (c.postId == postId && !c.deleted) n++;
        return n;
    }

private:
    static std::string rootKey(const std::string& postId) { return std::string("\x01root:") + postId; }

    static void requireNonBlank(const std::string& s, const char* name) {
        if (s.find_first_not_of(" \t\r\n") == std::string::npos) throw std::invalid_argument(std::string(name) + " required");
    }

    static std::string clean(const std::string& text) {
        auto first = text.find_first_not_of(" \t\r\n");
        if (first == std::string::npos) throw std::invalid_argument("text required");
        auto last = text.find_last_not_of(" \t\r\n");
        std::string t = text.substr(first, last - first + 1);
        if (t.size() > kMaxLength) throw std::invalid_argument("text too long");
        return t;
    }

    Comment insert(const std::string& postId, std::optional<std::string> parentId, const std::string& author,
                   const std::string& body, int depth) {
        long long seq = nextSeq_++;
        Comment c{"c" + std::to_string(seq), postId, parentId, author, body, seq, depth, false};
        byId_[c.id] = c;
        children_[parentId ? *parentId : rootKey(postId)].push_back(c.id);
        return c;
    }

    std::vector<CommentNode> build(const std::string& key) const {
        std::vector<CommentNode> out;
        auto it = children_.find(key);
        if (it == children_.end()) return out;
        for (const auto& id : it->second) out.push_back(CommentNode{byId_.at(id), build(id)});
        return out;
    }

    std::mutex mu_;
    std::unordered_map<std::string, Comment> byId_;
    std::unordered_map<std::string, std::vector<std::string>> children_;
    long long nextSeq_ = 1;
};
