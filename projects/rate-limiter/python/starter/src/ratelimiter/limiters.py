class TokenBucketLimiter:
    def __init__(self, capacity, refill_per_second, time):
        # TODO validate arguments and set up per-client state
        pass

    def try_acquire(self, client_id):
        # TODO refill the client's bucket based on elapsed time, then try to take one token
        raise NotImplementedError("TODO")


class SlidingWindowLimiter:
    def __init__(self, max_requests, window_millis, time):
        # TODO validate arguments and set up per-client state
        pass

    def try_acquire(self, client_id):
        # TODO drop timestamps that fell out of the window, then decide
        raise NotImplementedError("TODO")
