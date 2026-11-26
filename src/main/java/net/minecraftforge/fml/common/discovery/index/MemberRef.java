package net.minecraftforge.fml.common.discovery.index;

/**
 * One member as a query answered it: the owner, the name the loader defines, the descriptor that tells overloads
 * apart, and whether it is a method. The name is always the SRG one — an MCP name is a view, not something an index
 * record is allowed to hold.
 */
public record MemberRef(String owner, String name, String descriptor, boolean method) {
}
