/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.fs.shell;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;

import org.apache.hadoop.HadoopIllegalArgumentException;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.permission.AclEntry;
import org.apache.hadoop.fs.permission.AclEntryScope;
import org.apache.hadoop.fs.permission.AclEntryType;
import org.apache.hadoop.fs.permission.AclStatus;
import org.apache.hadoop.fs.permission.FsAction;
import org.apache.hadoop.util.StringUtils;

/**
 * Acl related operations
 */
@InterfaceAudience.Private
@InterfaceStability.Evolving
class AclCommands extends FsCommand {
  private static String GET_FACL = "getfacl";
  private static String SET_FACL = "setfacl";

  public static void registerCommands(CommandFactory factory) {
    factory.addClass(GetfaclCommand.class, "-" + GET_FACL);
    factory.addClass(SetfaclCommand.class, "-" + SET_FACL);
  }

  /**
   * Implementing the '-getfacl' command for the the FsShell.
   */
  public static class GetfaclCommand extends FsCommand {
    public static String NAME = GET_FACL;
    public static String USAGE = "[-R] <path>";
    public static String DESCRIPTION = "Displays the Access Control Lists"
        + " (ACLs) of files and directories. If a directory has a default ACL,"
        + " then getfacl also displays the default ACL.\n"
        + "-R: List the ACLs of all files and directories recursively.\n"
        + "<path>: File or directory to list.\n";

    @Override
    protected void processOptions(LinkedList<String> args) throws IOException {
      CommandFormat cf = new CommandFormat(0, Integer.MAX_VALUE, "R");
      cf.parse(args);
      setRecursive(cf.getOpt("R"));
      if (args.isEmpty()) {
        throw new HadoopIllegalArgumentException("<path> is missing");
      }
      if (args.size() > 1) {
        throw new HadoopIllegalArgumentException("Too many arguments");
      }
    }

    @Override
    protected void processPath(PathData item) throws IOException {
      AclStatus aclStatus = item.fs.getAclStatus(item.path);
      out.println("# file: " + item.path);
      out.println("# owner: " + aclStatus.getOwner());
      out.println("# group: " + aclStatus.getGroup());
      List<AclEntry> entries = aclStatus.getEntries();
      if (aclStatus.isStickyBit()) {
        String stickyFlag = "T";
        for (AclEntry aclEntry : entries) {
          if (aclEntry.getType() == AclEntryType.OTHER
              && aclEntry.getScope() == AclEntryScope.ACCESS
              && aclEntry.getPermission().implies(FsAction.EXECUTE)) {
            stickyFlag = "t";
            break;
          }
        }
        out.println("# flags: --" + stickyFlag);
      }
      for (AclEntry entry : entries) {
        out.println(entry);
      }
    }
  }

  /**
   * Implementing the '-setfacl' command for the the FsShell.
   */
  public static class SetfaclCommand extends FsCommand {
    public static String NAME = SET_FACL;
    public static String USAGE = "[-R] [{-b|-k} {-m|-x <acl_spec>} <path>]"
        + "|[--set <acl_spec> <path>]";
    public static String DESCRIPTION = "Sets Access Control Lists (ACLs)"
        + " of files and directories.\n" 
        + "Options:\n"
        + "-b :Remove all but the base ACL entries. The entries for user,"
        + " group and others are retained for compatibility with permission "
        + "bits.\n" 
        + "-k :Remove the default ACL.\n"
        + "-R :Apply operations to all files and directories recursively.\n"
        + "-m :Modify ACL. New entries are added to the ACL, and existing"
        + " entries are retained.\n"
        + "-x :Remove specified ACL entries. Other ACL entries are retained.\n"
        + "--set :Fully replace the ACL, discarding all existing entries."
        + " The <acl_spec> must include entries for user, group, and others"
        + " for compatibility with permission bits.\n"
        + "<acl_spec>: Comma separated list of ACL entries.\n"
        + "<path>: File or directory to modify.\n";

    private static final String DEFAULT = "default";

    Path path = null;
    CommandFormat cf = new CommandFormat(0, Integer.MAX_VALUE, "b", "k", "R",
        "m", "x", "-set");
    List<AclEntry> aclEntries = null;

    @Override
    protected void processOptions(LinkedList<String> args) throws IOException {
      cf.parse(args);
      setRecursive(cf.getOpt("R"));
      // Mix of remove and modify acl flags are not allowed
      boolean bothRemoveOptions = cf.getOpt("b") && cf.getOpt("k");
      boolean bothModifyOptions = cf.getOpt("m") && cf.getOpt("x");
      boolean oneRemoveOption = cf.getOpt("b") || cf.getOpt("k");
      boolean oneModifyOption = cf.getOpt("m") || cf.getOpt("x");
      boolean setOption = cf.getOpt("-set");
      if ((bothRemoveOptions || bothModifyOptions)
          || (oneRemoveOption && oneModifyOption)
          || (setOption && (oneRemoveOption || oneModifyOption))) {
        throw new HadoopIllegalArgumentException(
            "Specified flags contains both remove and modify flags");
      }

      // Only -m, -x and --set expects <acl_spec>
      if (oneModifyOption || setOption) {
        if (args.size() < 2) {
          throw new HadoopIllegalArgumentException("<acl_spec> is missing");
        }
        aclEntries = parseAclSpec(args.removeFirst());
      }

      if (args.isEmpty()) {
        throw new HadoopIllegalArgumentException("<path> is missing");
      }
      if (args.size() > 1) {
        throw new HadoopIllegalArgumentException("Too many arguments");
      }
      path = new Path(args.removeFirst());
    }

    @Override
    protected void processPath(PathData item) throws IOException {
      if (cf.getOpt("b")) {
        item.fs.removeAcl(item.path);
      } else if (cf.getOpt("k")) {
        item.fs.removeDefaultAcl(item.path);
      } else if (cf.getOpt("m")) {
        item.fs.modifyAclEntries(item.path, aclEntries);
      } else if (cf.getOpt("x")) {
        item.fs.removeAclEntries(item.path, aclEntries);
      } else if (cf.getOpt("-set")) {
        item.fs.setAcl(path, aclEntries);
      }
    }

    /**
     * Parse the aclSpec and returns the list of AclEntry objects.
     * 
     * @param aclSpec
     * @return
     */
    private List<AclEntry> parseAclSpec(String aclSpec) {
      List<AclEntry> aclEntries = new ArrayList<AclEntry>();
      Collection<String> aclStrings = StringUtils.getStringCollection(aclSpec,
          ",");
      for (String aclStr : aclStrings) {
        AclEntry.Builder builder = new AclEntry.Builder();
        // Here "::" represent one empty string.
        // StringUtils.getStringCollection() will ignore this.
        String[] split = aclSpec.split(":");
        if (split.length != 3
            && !(split.length == 4 && DEFAULT.equals(split[0]))) {
          throw new HadoopIllegalArgumentException("Invalid <aclSpec> : "
              + aclStr);
        }
        int index = 0;
        if (split.length == 4) {
          assert DEFAULT.equals(split[0]);
          // default entry
          index++;
          builder.setScope(AclEntryScope.DEFAULT);
        }
        String type = split[index++];
        AclEntryType aclType = null;
        try {
          aclType = Enum.valueOf(AclEntryType.class, type.toUpperCase());
          builder.setType(aclType);
        } catch (IllegalArgumentException iae) {
          throw new HadoopIllegalArgumentException(
              "Invalid type of acl in <aclSpec> :" + aclStr);
        }

        builder.setName(split[index++]);

        String permission = split[index++];
        FsAction fsAction = FsAction.getFsAction(permission);
        if (null == fsAction) {
          throw new HadoopIllegalArgumentException(
              "Invalid permission in <aclSpec> : " + aclStr);
        }
        builder.setPermission(fsAction);
        aclEntries.add(builder.build());
      }
      return aclEntries;
    }
  }
}