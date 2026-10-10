// Package config loads service configuration from config/<APP_ENV>.yaml with environment-variable overrides.
//
// Environments: local, dev, uat, prod. Secrets are never stored in YAML; they come from env vars that
// Kubernetes populates through External Secrets (AWS Secrets Manager, GCP Secret Manager or Azure Key Vault).
package config

import (
	"errors"
	"fmt"
	"io/fs"
	"os"
	"reflect"
	"strconv"
	"strings"
	"time"

	"gopkg.in/yaml.v3"
)

// Environments supported by every service.
const (
	EnvLocal = "local"
	EnvDev   = "dev"
	EnvUAT   = "uat"
	EnvProd  = "prod"
)

// Loader fills a config struct for the current environment.
type Loader interface {
	// Load reads config/<env>.yaml from fsys (if present) into target, then applies `env:"NAME"` overrides.
	Load(target any) error
	// Env returns the resolved environment name.
	Env() string
}

type loaderImpl struct {
	fsys fs.FS
	env  string
	look func(string) (string, bool)
}

var _ Loader = (*loaderImpl)(nil)

// NewLoader returns a Loader for APP_ENV (default local) reading YAML files from fsys.
func NewLoader(fsys fs.FS) Loader {
	env := strings.ToLower(strings.TrimSpace(os.Getenv("APP_ENV")))
	if env == "" {
		env = EnvLocal
	}
	return &loaderImpl{fsys: fsys, env: env, look: os.LookupEnv}
}

// NewLoaderFor is NewLoader with an explicit environment and lookup function (used in tests).
func NewLoaderFor(fsys fs.FS, env string, lookup func(string) (string, bool)) Loader {
	return &loaderImpl{fsys: fsys, env: env, look: lookup}
}

func (l *loaderImpl) Env() string { return l.env }

func (l *loaderImpl) Load(target any) error {
	switch l.env {
	case EnvLocal, EnvDev, EnvUAT, EnvProd:
	default:
		return fmt.Errorf("config: unknown APP_ENV %q", l.env)
	}
	if l.fsys != nil {
		raw, err := fs.ReadFile(l.fsys, "config/"+l.env+".yaml")
		switch {
		case err == nil:
			if err := yaml.Unmarshal(raw, target); err != nil {
				return fmt.Errorf("config: parse %s.yaml: %w", l.env, err)
			}
		case errors.Is(err, fs.ErrNotExist):
		default:
			return fmt.Errorf("config: read %s.yaml: %w", l.env, err)
		}
	}
	return l.applyEnv(reflect.ValueOf(target))
}

func (l *loaderImpl) applyEnv(v reflect.Value) error {
	if v.Kind() != reflect.Pointer || v.Elem().Kind() != reflect.Struct {
		return errors.New("config: target must be a pointer to a struct")
	}
	v = v.Elem()
	t := v.Type()
	for i := range t.NumField() {
		field := t.Field(i)
		fv := v.Field(i)
		if field.Type.Kind() == reflect.Struct && field.Type != reflect.TypeFor[time.Duration]() {
			if err := l.applyEnv(fv.Addr()); err != nil {
				return err
			}
			continue
		}
		name := field.Tag.Get("env")
		if name == "" {
			continue
		}
		raw, ok := l.look(name)
		if !ok {
			continue
		}
		if err := set(fv, raw); err != nil {
			return fmt.Errorf("config: %s: %w", name, err)
		}
	}
	return nil
}

func set(fv reflect.Value, raw string) error {
	if fv.Type() == reflect.TypeFor[time.Duration]() {
		d, err := time.ParseDuration(raw)
		if err != nil {
			return fmt.Errorf("parse duration: %w", err)
		}
		fv.SetInt(int64(d))
		return nil
	}
	switch fv.Kind() {
	case reflect.String:
		fv.SetString(raw)
	case reflect.Int, reflect.Int64, reflect.Int32:
		n, err := strconv.ParseInt(raw, 10, 64)
		if err != nil {
			return fmt.Errorf("parse int: %w", err)
		}
		fv.SetInt(n)
	case reflect.Bool:
		b, err := strconv.ParseBool(raw)
		if err != nil {
			return fmt.Errorf("parse bool: %w", err)
		}
		fv.SetBool(b)
	case reflect.Float64:
		f, err := strconv.ParseFloat(raw, 64)
		if err != nil {
			return fmt.Errorf("parse float: %w", err)
		}
		fv.SetFloat(f)
	case reflect.Slice:
		if fv.Type().Elem().Kind() != reflect.String {
			return fmt.Errorf("unsupported slice type %s", fv.Type())
		}
		parts := strings.Split(raw, ",")
		out := reflect.MakeSlice(fv.Type(), 0, len(parts))
		for _, p := range parts {
			if p = strings.TrimSpace(p); p != "" {
				out = reflect.Append(out, reflect.ValueOf(p))
			}
		}
		fv.Set(out)
	default:
		return fmt.Errorf("unsupported kind %s", fv.Kind())
	}
	return nil
}
