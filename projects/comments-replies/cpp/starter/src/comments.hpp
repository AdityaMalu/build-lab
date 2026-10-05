#pragma once

#include "comments_models.hpp"

class CommentService {
public:
    Comment addComment(const std::string& postId, const std::string& author, const std::string& text) {
        (void)postId;
        (void)author;
        (void)text;
        throw std::logic_error("TODO");
    }

    Comment reply(const std::string& parentId, const std::string& author, const std::string& text) {
        (void)parentId;
        (void)author;
        (void)text;
        throw std::logic_error("TODO");
    }

    std::vector<CommentNode> getThread(const std::string& postId) {
        (void)postId;
        throw std::logic_error("TODO");
    }

    void remove(const std::string& commentId, const std::string& requester) {
        (void)commentId;
        (void)requester;
        throw std::logic_error("TODO");
    }

    int countVisible(const std::string& postId) {
        (void)postId;
        throw std::logic_error("TODO");
    }

private:
    // TODO: storage
};
