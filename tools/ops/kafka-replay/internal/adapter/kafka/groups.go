package kafka

import (
	"context"
	"errors"
	"fmt"

	"github.com/twmb/franz-go/pkg/kerr"
	"github.com/twmb/franz-go/pkg/kmsg"

	"github.com/billionairedev24/diaspora-commerce/tools/ops/kafka-replay/internal/port"
)

type groupsImpl struct {
	cluster *Cluster
}

var _ port.GroupInspector = (*groupsImpl)(nil)

// NewGroupInspector returns a GroupInspector backed by DescribeGroups.
func NewGroupInspector(c *Cluster) port.GroupInspector { return &groupsImpl{cluster: c} }

func (g *groupsImpl) State(ctx context.Context, group string) (port.GroupState, error) {
	req := kmsg.NewPtrDescribeGroupsRequest()
	req.Groups = []string{group}
	resp, err := req.RequestWith(ctx, g.cluster.client)
	if err != nil {
		return port.GroupState{}, fmt.Errorf("describe group %s: %w", group, err)
	}
	if len(resp.Groups) != 1 {
		return port.GroupState{}, fmt.Errorf("describe group %s: unexpected answer", group)
	}
	d := resp.Groups[0]
	if code := kerr.ErrorForCode(d.ErrorCode); code != nil && !errors.Is(code, kerr.GroupIDNotFound) {
		return port.GroupState{}, fmt.Errorf("describe group %s: %w", group, code)
	}
	state := d.State
	if state == "" {
		state = "Dead"
	}
	return port.GroupState{State: state, Members: len(d.Members)}, nil
}
