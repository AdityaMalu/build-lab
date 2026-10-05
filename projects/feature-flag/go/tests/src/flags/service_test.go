package flags

import (
	"errors"
	"fmt"
	"sync"
	"testing"
)

func user(id string) User { return User{ID: id} }

func simple(key string, enabled bool, rollout int) Flag {
	return Flag{Key: key, Enabled: enabled, RolloutPercent: rollout}
}

func mustDefine(t *testing.T, s *Service, f Flag) {
	t.Helper()
	if err := s.Define(f); err != nil {
		t.Fatalf("Define(%q) failed: %v", f.Key, err)
	}
}

func eval(t *testing.T, s *Service, key string, u User) string {
	t.Helper()
	v, err := s.Evaluate(key, u)
	if err != nil {
		t.Fatalf("Evaluate(%q, %q) failed: %v", key, u.ID, err)
	}
	return v
}

// Test: FNV-1a bucketing matches the reference vectors
func TestFNVVectors(t *testing.T) {
	cases := []struct {
		key     string
		buckets uint32
		want    uint32
	}{{"", 1000, 261}, {"a", 2147483647, 1678518573}, {"foobar", 2147483647, 1067252073}, {"a", 100, 20}}
	for _, c := range cases {
		if got := Bucket(c.key, c.buckets); got != c.want {
			t.Fatalf("Bucket(%q, %d) = %d, want %d", c.key, c.buckets, got, c.want)
		}
	}
}

// Test: bucketing is in range and deterministic
func TestBucketRange(t *testing.T) {
	for i := 0; i < 1000; i++ {
		k := fmt.Sprintf("user-%d", i)
		b := Bucket(k, 100)
		if b >= 100 || b != Bucket(k, 100) {
			t.Fatalf("Bucket(%q) = %d: out of range or not deterministic", k, b)
		}
	}
}

// Test: disabled flag is off, unknown flag is an error
func TestDisabledAndUnknown(t *testing.T) {
	s := NewService()
	mustDefine(t, s, simple("dark", false, 100))
	if v := eval(t, s, "dark", user("u1")); v != "off" {
		t.Fatalf("disabled: want off, got %q", v)
	}
	if _, err := s.Evaluate("nope", user("u1")); !errors.Is(err, ErrUnknownFlag) {
		t.Fatalf("unknown flag: want ErrUnknownFlag, got %v", err)
	}
	if _, err := s.Evaluate("dark", user(" ")); !errors.Is(err, ErrInvalidUser) {
		t.Fatalf("blank user: want ErrInvalidUser, got %v", err)
	}
}

// Test: rollout 0% and 100%
func TestRolloutExtremes(t *testing.T) {
	s := NewService()
	mustDefine(t, s, simple("none", true, 0))
	mustDefine(t, s, simple("all", true, 100))
	for i := 0; i < 500; i++ {
		u := user(fmt.Sprint("u", i))
		if eval(t, s, "none", u) != "off" || eval(t, s, "all", u) != "on" {
			t.Fatalf("user %s: 0%% must be off and 100%% on", u.ID)
		}
	}
}

// Test: rollout uses Bucket(flagKey:userId) < percent exactly
func TestRolloutExact(t *testing.T) {
	s := NewService()
	mustDefine(t, s, simple("checkout-v2", true, 30))
	on := 0
	for i := 0; i < 10000; i++ {
		id := fmt.Sprintf("user-%d", i)
		want := "off"
		if Bucket("checkout-v2:"+id, 100) < 30 {
			want = "on"
		}
		got := eval(t, s, "checkout-v2", user(id))
		if got != want {
			t.Fatalf("user %s: want %s, got %s", id, want, got)
		}
		if got == "on" {
			on++
		}
	}
	if on <= 2700 || on >= 3300 {
		t.Fatalf("about 30%% should be on, got %d", on)
	}
}

// Test: same answer across service instances (no stored state)
func TestDeterministicAcrossInstances(t *testing.T) {
	f := Flag{Key: "exp", Enabled: true, RolloutPercent: 50, Variants: []Variant{{"A", 50}, {"B", 50}}}
	one, two := NewService(), NewService()
	mustDefine(t, one, f)
	mustDefine(t, two, f)
	for i := 0; i < 1000; i++ {
		u := user(fmt.Sprint("x", i))
		if eval(t, one, "exp", u) != eval(t, two, "exp", u) {
			t.Fatalf("instances disagree for %s", u.ID)
		}
	}
}

// Test: variants follow the weighted split on the :variant: salt
func TestVariants(t *testing.T) {
	s := NewService()
	mustDefine(t, s, Flag{Key: "color", Enabled: true, RolloutPercent: 100,
		Variants: []Variant{{"red", 20}, {"green", 30}, {"blue", 50}}})
	counts := map[string]int{}
	for i := 0; i < 10000; i++ {
		id := fmt.Sprint("u", i)
		b := Bucket("color:variant:"+id, 100)
		want := "blue"
		if b < 20 {
			want = "red"
		} else if b < 50 {
			want = "green"
		}
		got := eval(t, s, "color", user(id))
		if got != want {
			t.Fatalf("user %s: want %s, got %s", id, want, got)
		}
		counts[got]++
	}
	if counts["red"] <= 1700 || counts["red"] >= 2300 || counts["blue"] <= 4500 || counts["blue"] >= 5500 {
		t.Fatalf("split looks wrong: %v", counts)
	}
}

// Test: a zero-weight variant is never served
func TestZeroWeight(t *testing.T) {
	s := NewService()
	mustDefine(t, s, Flag{Key: "z", Enabled: true, RolloutPercent: 100, Variants: []Variant{{"never", 0}, {"always", 100}}})
	for i := 0; i < 300; i++ {
		if v := eval(t, s, "z", user(fmt.Sprint("u", i))); v != "always" {
			t.Fatalf("got %q", v)
		}
	}
}

// Test: targeting rules win over rollout, first match wins
func TestRules(t *testing.T) {
	s := NewService()
	mustDefine(t, s, Flag{Key: "beta", Enabled: true, RolloutPercent: 0, Rules: []Rule{
		{"country", "IN", "india-beta"}, {"plan", "pro", "pro-beta"}, {"country", "IN", "never-reached"}}})
	cases := []struct {
		u    User
		want string
	}{
		{User{"1", map[string]string{"country": "IN", "plan": "pro"}}, "india-beta"},
		{User{"2", map[string]string{"country": "US", "plan": "pro"}}, "pro-beta"},
		{User{"3", map[string]string{"country": "US"}}, "off"},
		{User{"4", nil}, "off"},
	}
	for _, c := range cases {
		if got := eval(t, s, "beta", c.u); got != c.want {
			t.Fatalf("user %s: want %s, got %s", c.u.ID, c.want, got)
		}
	}
}

// Test: rules do not apply when the flag is disabled
func TestRulesWhenDisabled(t *testing.T) {
	s := NewService()
	mustDefine(t, s, Flag{Key: "k", Enabled: false, RolloutPercent: 100, Rules: []Rule{{"vip", "yes", "vip"}}})
	if v := eval(t, s, "k", User{"1", map[string]string{"vip": "yes"}}); v != "off" {
		t.Fatalf("want off, got %q", v)
	}
}

// Test: Define validates input and replaces by key
func TestDefineValidation(t *testing.T) {
	s := NewService()
	bad := []Flag{
		simple(" ", true, 10), simple("k", true, 101), simple("k", true, -1),
		{Key: "k", Enabled: true, RolloutPercent: 10, Variants: []Variant{{"a", 60}, {"b", 30}}},
		{Key: "k", Enabled: true, RolloutPercent: 10, Variants: []Variant{{"a", 110}, {"b", -10}}},
	}
	for _, f := range bad {
		if err := s.Define(f); !errors.Is(err, ErrInvalidFlag) {
			t.Fatalf("Define(%+v): want ErrInvalidFlag, got %v", f, err)
		}
	}
	mustDefine(t, s, simple("k", true, 100))
	if eval(t, s, "k", user("a")) != "on" {
		t.Fatal("want on")
	}
	mustDefine(t, s, simple("k", false, 100))
	if eval(t, s, "k", user("a")) != "off" {
		t.Fatal("redefinition replaces the flag")
	}
}

// Test: caller mutating its slices after Define has no effect
func TestDefensiveCopy(t *testing.T) {
	s := NewService()
	variants := []Variant{{"only", 100}}
	mustDefine(t, s, Flag{Key: "k", Enabled: true, RolloutPercent: 100, Variants: variants})
	variants[0] = Variant{"hacked", 100}
	if v := eval(t, s, "k", user("a")); v != "only" {
		t.Fatalf("want only, got %q", v)
	}
}

// Test: concurrent Define and Evaluate never see a broken flag
func TestConcurrency(t *testing.T) {
	s := NewService()
	a := Flag{Key: "live", Enabled: true, RolloutPercent: 100, Variants: []Variant{{"A", 100}}}
	b := Flag{Key: "live", Enabled: true, RolloutPercent: 100, Variants: []Variant{{"B", 50}, {"C", 50}}}
	mustDefine(t, s, a)
	var wg sync.WaitGroup
	for g := 0; g < 8; g++ {
		wg.Add(1)
		go func(g int) {
			defer wg.Done()
			for k := 0; k < 2000; k++ {
				if g == 0 {
					f := a
					if k%2 == 1 {
						f = b
					}
					_ = s.Define(f)
					continue
				}
				v, err := s.Evaluate("live", user(fmt.Sprint("u", k)))
				if err != nil || (v != "A" && v != "B" && v != "C") {
					t.Errorf("unexpected %q, %v", v, err)
					return
				}
			}
		}(g)
	}
	wg.Wait()
}
