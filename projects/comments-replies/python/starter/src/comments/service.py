MAX_LENGTH = 500
MAX_DEPTH = 3


class CommentService:
    def add_comment(self, post_id, author, text):
        raise NotImplementedError("TODO")

    def reply(self, parent_id, author, text):
        raise NotImplementedError("TODO")

    def get_thread(self, post_id):
        raise NotImplementedError("TODO")

    def delete(self, comment_id, requester):
        raise NotImplementedError("TODO")

    def count_visible(self, post_id):
        raise NotImplementedError("TODO")
