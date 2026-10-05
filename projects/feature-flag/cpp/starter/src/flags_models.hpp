#pragma once

#include <cstdint>
#include <map>
#include <stdexcept>
#include <string>
#include <vector>

/** If user.attributes[attribute] == equalsValue, serve variant. */
struct Rule {
    std::string attribute;
    std::string equalsValue;
    std::string variant;
};

struct Variant {
    std::string name;
    int weight = 0;
};

struct Flag {
    std::string key;
    bool enabled = false;
    int rolloutPercent = 0;
    std::vector<Rule> rules;
    std::vector<Variant> variants;
};

struct User {
    std::string id;
    std::map<std::string, std::string> attributes;
};
