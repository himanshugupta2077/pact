// Package tsship gives the Pact tablet build its own userspace Tailscale node,
// so reports reach the private receiver without taking Android's VPN slot.
package tsship

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"strconv"
	"strings"
	"sync"
	"time"

	"tailscale.com/ipn"
	"tailscale.com/net/netmon"
	"tailscale.com/tsnet"
)

var (
	mu      sync.Mutex
	srv     *tsnet.Server
	authURL string
)

var (
	ifMu       sync.Mutex
	interfaces []netmon.Interface
)

func init() {
	// Android denies app UIDs the netlink calls behind net.Interfaces, so Kotlin supplies the list.
	netmon.RegisterInterfaceGetter(func() ([]netmon.Interface, error) {
		ifMu.Lock()
		defer ifMu.Unlock()
		return interfaces, nil
	})
}

// SetInterfaces takes one line per interface: "name index mtu flags addr/prefix...",
// where flags holds u (up), l (loopback), p (point-to-point) and m (multicast).
func SetInterfaces(list, defaultName string) {
	var out []netmon.Interface
	for _, line := range strings.Split(list, "\n") {
		f := strings.Fields(line)
		if len(f) < 4 {
			continue
		}
		index, _ := strconv.Atoi(f[1])
		mtu, _ := strconv.Atoi(f[2])
		var flags net.Flags
		for _, c := range f[3] {
			switch c {
			case 'u':
				flags |= net.FlagUp | net.FlagRunning
			case 'l':
				flags |= net.FlagLoopback
			case 'p':
				flags |= net.FlagPointToPoint
			case 'm':
				flags |= net.FlagMulticast
			}
		}
		var addrs []net.Addr
		for _, a := range f[4:] {
			if ip, ipnet, err := net.ParseCIDR(a); err == nil {
				ipnet.IP = ip
				addrs = append(addrs, ipnet)
			}
		}
		out = append(out, netmon.Interface{
			Interface: &net.Interface{Index: index, MTU: mtu, Name: f[0], Flags: flags},
			AltAddrs:  addrs,
		})
	}
	ifMu.Lock()
	interfaces = out
	ifMu.Unlock()
	netmon.UpdateLastKnownDefaultRouteInterface(defaultName)
}

// Up starts the node if needed and waits until it is running or the timeout passes.
// When the node needs a login, the error is "needs login" and AuthURL returns the link.
func Up(dir, tmp, hostname, authKey string, timeoutSeconds int) error {
	mu.Lock()
	if srv == nil {
		os.Setenv("TS_NO_LOGS_NO_SUPPORT", "true")
		os.Setenv("TMPDIR", tmp)
		srv = &tsnet.Server{Dir: dir, Hostname: hostname, AuthKey: authKey, Logf: func(string, ...any) {}}
	}
	s := srv
	mu.Unlock()

	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(timeoutSeconds)*time.Second)
	defer cancel()
	lc, err := s.LocalClient()
	if err != nil {
		return err
	}
	watcher, err := lc.WatchIPNBus(ctx, ipn.NotifyInitialState)
	if err != nil {
		return err
	}
	defer watcher.Close()
	for {
		n, err := watcher.Next()
		if err != nil {
			if url := AuthURL(); url != "" {
				return errors.New("needs login")
			}
			return err
		}
		if n.BrowseToURL != nil && *n.BrowseToURL != "" {
			mu.Lock()
			authURL = *n.BrowseToURL
			mu.Unlock()
		}
		if n.State == nil {
			continue
		}
		switch *n.State {
		case ipn.Running:
			mu.Lock()
			authURL = ""
			mu.Unlock()
			return nil
		case ipn.NeedsLogin:
			// Asking for a login URL is harmless when one is already pending.
			_ = lc.StartLoginInteractive(ctx)
		}
	}
}

// AuthURL returns the pending interactive login link, if any.
func AuthURL() string {
	mu.Lock()
	defer mu.Unlock()
	return authURL
}

// Post sends body as JSON over the tailnet and returns the HTTP status.
func Post(url, token, body string, timeoutSeconds int) (int, error) {
	mu.Lock()
	s := srv
	mu.Unlock()
	if s == nil {
		return 0, errors.New("not started")
	}
	client := &http.Client{
		Timeout: time.Duration(timeoutSeconds) * time.Second,
		Transport: &http.Transport{
			DialContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
				return s.Dial(ctx, network, addr)
			},
		},
		CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse },
	}
	req, err := http.NewRequest(http.MethodPost, url, bytes.NewReader([]byte(body)))
	if err != nil {
		return 0, err
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer "+token)
	resp, err := client.Do(req)
	if err != nil {
		return 0, fmt.Errorf("post: %w", err)
	}
	defer resp.Body.Close()
	io.Copy(io.Discard, io.LimitReader(resp.Body, 1<<16))
	return resp.StatusCode, nil
}

// Close stops the node. A later Up starts it again from the saved state.
func Close() {
	mu.Lock()
	s := srv
	srv = nil
	mu.Unlock()
	if s != nil {
		s.Close()
	}
}
