// Sandbox probe (CI only): a static initializer in the code under test; every line must say BLOCKED or OK.
#include <arpa/inet.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <signal.h>
#include <sys/socket.h>
#include <sys/wait.h>
#include <unistd.h>

#include <cerrno>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>

namespace {

void report(const char* name, bool allowed) {
    if (allowed) std::printf("PROBE ALLOWED: %s\n", name);
    else std::printf("PROBE BLOCKED: %s - %s\n", name, std::strerror(errno));
    std::fflush(stdout);
}

struct Probe {
    Probe() {
        int fd = open("/etc/shadow", O_RDONLY);
        report("read /etc/shadow", fd >= 0);
        if (fd >= 0) close(fd);

        fd = open("/app/web/pwned.txt", O_WRONLY | O_CREAT, 0644);
        report("write into the app", fd >= 0);
        if (fd >= 0) close(fd);

        int s = socket(AF_INET, SOCK_STREAM, 0);
        report("internet socket", s >= 0);
        if (s >= 0) close(s);

        pid_t p = fork();
        if (p == 0) _exit(0);
        report("start a process", p > 0);
        if (p > 0) waitpid(p, nullptr, 0);

        report("signal pid 1", kill(1, 0) == 0);

        const char* tmp = std::getenv("TMPDIR");
        std::string path = std::string(tmp ? tmp : "/tmp") + "/okXXXXXX";
        fd = mkstemp(path.data());
        if (fd >= 0) {
            close(fd);
            std::printf("PROBE OK: write own temp file\n");
        } else {
            std::printf("PROBE BROKEN: own temp file - %s\n", std::strerror(errno));
        }
        std::fflush(stdout);
    }
} probeInstance;

}  // namespace
