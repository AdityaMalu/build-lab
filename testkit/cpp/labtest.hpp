// Minimal test kit for MachineCodingLab's C++ projects (header-only, standard library only).
//
//   LAB_TEST(SuiteName, methodName, "readable name") { ...; ASSERT_EQ(expected, actual, "message"); }
//
// Each test runs on its own thread with a timeout (10 s by default, LAB_TEST_TIMEOUT to change it).
#pragma once

#include <atomic>
#include <functional>
#include <latch>
#include <mutex>
#include <optional>
#include <sstream>
#include <stdexcept>
#include <string>
#include <thread>
#include <type_traits>
#include <utility>
#include <vector>

namespace labtest {

struct Failure : std::exception {
    std::string msg;
    explicit Failure(std::string m) : msg(std::move(m)) {}
    const char* what() const noexcept override { return msg.c_str(); }
};

struct TestCase {
    const char* suite;
    const char* method;
    const char* name;
    std::function<void()> fn;
    int timeoutMs;
};

inline std::vector<TestCase>& registry() {
    static std::vector<TestCase> r;
    return r;
}

struct Registrar {
    Registrar(const char* suite, const char* method, const char* name, void (*fn)(), int timeoutMs = 10000) {
        registry().push_back({suite, method, name, fn, timeoutMs});
    }
};

template <class T>
std::string show(const T& v) {
    if constexpr (std::is_same_v<T, bool>) {
        return v ? "true" : "false";
    } else if constexpr (std::is_convertible_v<const T&, std::string>) {
        return "\"" + std::string(v) + "\"";
    } else if constexpr (requires(std::ostream& o, const T& x) { o << x; }) {
        std::ostringstream os;
        os << v;
        return os.str();
    } else {
        return "<value>";
    }
}

template <class T>
std::string show(const std::optional<T>& v) {
    return v ? "optional(" + show(*v) + ")" : "nullopt";
}

[[noreturn]] inline void fail(const std::string& message) { throw Failure(message); }

/** Starts `threads` workers behind a shared gate and calls task(index) in each; rethrows the first failure. */
inline void runConcurrently(int threads, const std::function<void(int)>& task) {
    std::latch go(1);
    std::mutex mu;
    std::exception_ptr first;
    std::vector<std::thread> workers;
    workers.reserve(threads);
    for (int i = 0; i < threads; i++) {
        workers.emplace_back([&, i] {
            go.wait();
            try {
                task(i);
            } catch (...) {
                std::lock_guard<std::mutex> lock(mu);
                if (!first) first = std::current_exception();
            }
        });
    }
    go.count_down();
    for (auto& w : workers) w.join();
    if (first) std::rethrow_exception(first);
}

}  // namespace labtest

#define LAB_CAT2(a, b) a##b
#define LAB_CAT(a, b) LAB_CAT2(a, b)

#define LAB_TEST_TIMEOUT(Suite, Method, Name, Ms)                                                         \
    static void LAB_CAT(Suite, LAB_CAT(_, Method))();                                                   \
    static ::labtest::Registrar LAB_CAT(lab_reg_, LAB_CAT(Suite, LAB_CAT(_, Method)))(#Suite, #Method, \
                                                                                      Name,             \
                                                                                      &LAB_CAT(Suite, LAB_CAT(_, Method)), Ms); \
    static void LAB_CAT(Suite, LAB_CAT(_, Method))()

#define LAB_TEST(Suite, Method, Name) LAB_TEST_TIMEOUT(Suite, Method, Name, 10000)

#define FAIL(msg) ::labtest::fail(std::string(msg))

#define ASSERT_TRUE(cond, msg)                                  \
    do {                                                        \
        if (!(cond)) ::labtest::fail(std::string(msg));         \
    } while (0)

#define ASSERT_FALSE(cond, msg)                                 \
    do {                                                        \
        if ((cond)) ::labtest::fail(std::string(msg));          \
    } while (0)

#define ASSERT_EQ(expected, actual, msg)                                                                   \
    do {                                                                                                   \
        const auto& lab_e = (expected);                                                                    \
        const auto& lab_a = (actual);                                                                      \
        if (!(lab_e == lab_a))                                                                             \
            ::labtest::fail(std::string(msg) + " ==> expected <" + ::labtest::show(lab_e) + "> but was <" + \
                            ::labtest::show(lab_a) + ">");                                                 \
    } while (0)

#define ASSERT_NE(unexpected, actual, msg)                                                              \
    do {                                                                                                \
        const auto& lab_u = (unexpected);                                                               \
        const auto& lab_a = (actual);                                                                   \
        if (lab_u == lab_a) ::labtest::fail(std::string(msg) + " ==> did not expect <" + ::labtest::show(lab_a) + ">"); \
    } while (0)

#define ASSERT_THROWS(Type, statement, msg)                                                                 \
    do {                                                                                                    \
        bool lab_thrown = false;                                                                            \
        try {                                                                                               \
            statement;                                                                                      \
        } catch (const Type&) {                                                                             \
            lab_thrown = true;                                                                              \
        } catch (const ::labtest::Failure&) {                                                               \
            throw;                                                                                          \
        } catch (const std::exception& lab_ex) {                                                            \
            ::labtest::fail(std::string(msg) + " ==> expected " #Type " but got: " + lab_ex.what());        \
        } catch (...) {                                                                                     \
            ::labtest::fail(std::string(msg) + " ==> expected " #Type " but got an unknown exception");     \
        }                                                                                                   \
        if (!lab_thrown) ::labtest::fail(std::string(msg) + " ==> expected " #Type " but nothing was thrown"); \
    } while (0)
