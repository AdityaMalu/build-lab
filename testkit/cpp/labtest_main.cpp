// Runs the registered LAB_TEST cases. Arguments "Suite#method" select tests (default: all).
//
// Output (parsed by the lab server):
//   PASS|Suite|method|name|millis
//   FAIL|Suite|method|name|millis|message
//   RESULT|passed|failed|total
#include "labtest.hpp"

#include <algorithm>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <future>
#include <iostream>
#include <map>
#include <memory>
#include <set>
#include <typeinfo>

#if defined(__GNUG__)
#include <cxxabi.h>
#endif

namespace {

std::string typeName(const std::exception& e) {
#if defined(__GNUG__)
    int status = 0;
    char* demangled = abi::__cxa_demangle(typeid(e).name(), nullptr, nullptr, &status);
    std::string out = status == 0 && demangled ? demangled : typeid(e).name();
    std::free(demangled);
    return out;
#else
    return typeid(e).name();
#endif
}

std::string oneLine(std::string s) {
    for (char& c : s) {
        if (c == '\n' || c == '\r') c = ' ';
    }
    return s;
}

}  // namespace

int main(int argc, char** argv) {
    std::map<std::string, std::set<std::string>> wanted;
    for (int i = 1; i < argc; i++) {
        std::string a = argv[i];
        auto hash = a.find('#');
        if (hash != std::string::npos) wanted[a.substr(0, hash)].insert(a.substr(hash + 1));
    }

    auto tests = labtest::registry();
    std::stable_sort(tests.begin(), tests.end(), [](const labtest::TestCase& x, const labtest::TestCase& y) {
        int s = std::string(x.suite).compare(y.suite);
        return s != 0 ? s < 0 : std::string(x.method) < std::string(y.method);
    });

    int passed = 0, failed = 0;
    for (const auto& t : tests) {
        if (!wanted.empty()) {
            auto it = wanted.find(t.suite);
            if (it == wanted.end() || !it->second.count(t.method)) continue;
        }
        auto promise = std::make_shared<std::promise<std::string>>();
        auto result = promise->get_future();
        auto fn = t.fn;
        auto start = std::chrono::steady_clock::now();
        std::thread worker([promise, fn] {
            try {
                fn();
                promise->set_value("");
            } catch (const labtest::Failure& f) {
                promise->set_value(f.msg.empty() ? "assertion failed" : f.msg);
            } catch (const std::exception& e) {
                std::string what = e.what();
                promise->set_value(typeName(e) + (what.empty() ? "" : ": " + what));
            } catch (...) {
                promise->set_value("unknown exception");
            }
        });
        std::string error;
        if (result.wait_for(std::chrono::milliseconds(t.timeoutMs)) == std::future_status::timeout) {
            worker.detach();  // can't kill a thread; the process exits at the end anyway
            error = "timed out after " + std::to_string(t.timeoutMs) + " ms (deadlock or infinite loop?)";
        } else {
            worker.join();
            error = result.get();
        }
        long ms = (long)std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - start).count();
        std::string name = oneLine(t.name);
        std::replace(name.begin(), name.end(), '|', '/');
        if (error.empty()) {
            passed++;
            std::cout << "PASS|" << t.suite << "|" << t.method << "|" << name << "|" << ms << std::endl;
        } else {
            failed++;
            std::cout << "FAIL|" << t.suite << "|" << t.method << "|" << name << "|" << ms << "|" << oneLine(error)
                      << std::endl;
        }
    }
    std::cout << "RESULT|" << passed << "|" << failed << "|" << (passed + failed) << std::endl;
    std::cout.flush();
    std::_Exit(failed == 0 ? 0 : 1);  // don't wait for threads left running by the code under test
}
