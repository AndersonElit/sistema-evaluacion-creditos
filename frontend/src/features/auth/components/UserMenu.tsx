import * as Dropdown from '@radix-ui/react-dropdown-menu';
import { LogOut, User } from 'lucide-react';
import { useAuth } from '../hooks/useAuth';
import { Button } from '@/shared/ui/Button';
import { Badge } from '@/shared/ui/Badge';

export const UserMenu = () => {
  const { user, logout } = useAuth();
  if (!user) return null;
  return (
    <Dropdown.Root>
      <Dropdown.Trigger asChild>
        <Button variant="ghost" size="sm" className="gap-2">
          <User className="h-4 w-4" aria-hidden />
          <span className="hidden sm:inline">{user.email}</span>
        </Button>
      </Dropdown.Trigger>
      <Dropdown.Portal>
        <Dropdown.Content
          align="end"
          sideOffset={8}
          className="z-50 min-w-56 rounded-md border bg-card p-2 shadow-lg"
        >
          <div className="px-2 py-1.5">
            <p className="text-sm font-medium">{user.name}</p>
            <p className="text-xs text-muted-foreground">{user.email}</p>
            <div className="mt-2 flex flex-wrap gap-1">
              {user.roles.map((r) => (
                <Badge key={r} tone="primary">
                  {r}
                </Badge>
              ))}
            </div>
          </div>
          <Dropdown.Separator className="my-1 h-px bg-border" />
          <Dropdown.Item
            onSelect={logout}
            className="flex cursor-pointer items-center gap-2 rounded-sm px-2 py-1.5 text-sm outline-none data-[highlighted]:bg-muted"
          >
            <LogOut className="h-4 w-4" aria-hidden />
            Cerrar sesión
          </Dropdown.Item>
        </Dropdown.Content>
      </Dropdown.Portal>
    </Dropdown.Root>
  );
};
